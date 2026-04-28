package zed.rainxch.githubstore.ingest

import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps the passthrough-ingested long tail fresh.
 *
 * The Python fetcher re-hydrates the curated set (trending / new-releases /
 * most-popular × 4 platforms + 5 topic buckets) daily, but repos added via
 * on-demand passthrough never get refreshed — their stars, releases, and
 * download_count drift forever after the first ingest. This worker walks
 * the passthrough tail in batches, ordered by oldest indexed_at, re-fetching
 * metadata + releases and upserting.
 *
 * Pace: 50 repos per cycle, one cycle per hour, 1-second pacing per repo =
 * ~50 seconds of work per cycle, ~1200 repos refreshed per day. The full
 * tail recycles every ~3 days for a 3400-repo index.
 */
class RepoRefreshWorker(
    private val githubSearch: GitHubSearchClient,
    private val supervisor: WorkerSupervisor? = null,
) {
    private val log = LoggerFactory.getLogger(RepoRefreshWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val startupDelay = 10.minutes
    private val cycleInterval = 1.hours
    private val batchSize = 50
    // Pacing between per-repo upstream calls. Default 1s ≈ 50s total per
    // cycle, ~1200 repos/day. Tunable via REPO_REFRESH_PACE_MS so an operator
    // can speed things up after backlog spikes (or slow them down if the
    // rotation pool is tight).
    private val pacePerRepo: kotlin.time.Duration =
        (System.getenv("REPO_REFRESH_PACE_MS")?.toLongOrNull() ?: 1_000L)
            .coerceAtLeast(0L).milliseconds

    private val advisoryLockId: Long = 911_002L

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            try {
                if (tryRunCycle()) {
                    supervisor?.recordTick(WORKER_NAME)
                }
            } catch (e: Exception) {
                log.error("Repo refresh cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    private suspend fun tryRunCycle(): Boolean {
        if (!acquireAdvisoryLock()) {
            log.info("RepoRefresh skipped: advisory lock held by another instance")
            return false
        }
        try {
            processBatch()
            return true
        } finally {
            releaseAdvisoryLock()
        }
    }

    private fun acquireAdvisoryLock(): Boolean = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
            ps.setLong(1, advisoryLockId)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    private fun releaseAdvisoryLock() {
        try {
            transaction {
                val conn = TransactionManager.current().connection.connection as java.sql.Connection
                conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                    ps.setLong(1, advisoryLockId)
                    ps.executeQuery().close()
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to release advisory lock {}: {}", advisoryLockId, e.message)
        }
    }

    private suspend fun processBatch() {
        val candidates = pickOldest(batchSize)
        if (candidates.isEmpty()) {
            log.debug("No passthrough repos to refresh")
            return
        }
        log.info("Refreshing {} passthrough repos (oldest first)", candidates.size)

        var refreshed = 0
        var gone = 0
        var archived = 0
        var stale = 0
        var failed = 0

        for ((id, fullName) in candidates) {
            when (val result = githubSearch.refreshRepo(fullName)) {
                is GitHubSearchClient.RefreshResult.Ok -> {
                    githubSearch.persist(result.repo)
                    refreshed++
                }
                GitHubSearchClient.RefreshResult.Gone -> {
                    // Mark indexed_at to now so we don't keep hammering the 404.
                    // Real removal from Meili could be wired later; for now we
                    // leave the Postgres row because events/repo_signals FK to it.
                    touch(id)
                    gone++
                    log.info("Repo gone (404): {}", fullName)
                }
                GitHubSearchClient.RefreshResult.Archived -> {
                    touch(id)
                    archived++
                    log.info("Repo archived/disabled: {}", fullName)
                }
                is GitHubSearchClient.RefreshResult.NoUsableRelease -> {
                    touch(id)
                    stale++
                }
                GitHubSearchClient.RefreshResult.TransientFailure -> {
                    // Don't touch indexed_at so the next cycle retries.
                    failed++
                }
            }
            delay(pacePerRepo)
        }

        log.info(
            "Refresh cycle done: {} refreshed, {} gone, {} archived, {} no-release, {} transient-fail",
            refreshed, gone, archived, stale, failed,
        )
    }

    // Oldest-first over passthrough-only: repos NOT in any curated category or
    // topic bucket (those get refreshed by the Python fetcher daily).
    //
    // Anti-join via LEFT JOIN ... IS NULL — the previous NOT IN form forced
    // a sequential scan of repos plus two uncorrelated subquery scans on
    // repo_categories / repo_topic_buckets. The LEFT JOIN form combined with
    // V9's idx_repos_indexed_at lets Postgres index-scan the order-by and
    // hash-anti-join the two membership tables.
    private fun pickOldest(limit: Int): List<Pair<Long, String>> = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val out = mutableListOf<Pair<Long, String>>()
        conn.prepareStatement(
            """
            SELECT r.id, r.full_name
            FROM repos r
            LEFT JOIN repo_categories rc ON rc.repo_id = r.id
            LEFT JOIN repo_topic_buckets rtb ON rtb.repo_id = r.id
            WHERE rc.repo_id IS NULL AND rtb.repo_id IS NULL
            ORDER BY r.indexed_at ASC NULLS FIRST
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(rs.getLong("id") to rs.getString("full_name"))
                }
            }
        }
        out
    }

    private fun touch(repoId: Long) {
        transaction {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            conn.prepareStatement("UPDATE repos SET indexed_at = ? WHERE id = ?").use { ps ->
                ps.setObject(1, OffsetDateTime.now())
                ps.setLong(2, repoId)
                ps.executeUpdate()
            }
        }
    }

    companion object {
        const val WORKER_NAME = "repo_refresh"
    }
}
