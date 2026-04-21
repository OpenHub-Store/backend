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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
) {
    private val log = LoggerFactory.getLogger(RepoRefreshWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val startupDelay = 10.minutes
    private val cycleInterval = 1.hours
    private val batchSize = 50
    private val pacePerRepo = 1.seconds

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            try {
                processBatch()
            } catch (e: Exception) {
                log.error("Repo refresh cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
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
    private fun pickOldest(limit: Int): List<Pair<Long, String>> = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val out = mutableListOf<Pair<Long, String>>()
        conn.prepareStatement(
            """
            SELECT r.id, r.full_name
            FROM repos r
            WHERE r.id NOT IN (SELECT repo_id FROM repo_categories)
              AND r.id NOT IN (SELECT repo_id FROM repo_topic_buckets)
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
}
