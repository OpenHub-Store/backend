package zed.rainxch.githubstore.ingest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.MeiliScoreUpdate
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.ranking.SearchScore
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Aggregates Events rows into RepoSignals once an hour, then computes a unified
 * search_score per repo and pushes it to Postgres + Meilisearch.
 *
 * Formula (each factor in [0,1], weights sum to 1):
 *   search_score =
 *       0.40 * log10(stars + 1) / 6        // star scale, ~1M stars ceiling
 *     + 0.30 * ctr_score                   // Laplace-smoothed click-through rate
 *     + 0.20 * install_success_rate        // Laplace-smoothed install success
 *     + 0.10 * exp(-days_since_release/90) // 90-day half-life freshness
 *
 * Cold-start repos (no events) fall back to stars + freshness only — still useful
 * ordering but behavioral signals pull winners above them quickly.
 */
class SignalAggregationWorker(
    private val meilisearchClient: MeilisearchClient,
) {
    private val log = LoggerFactory.getLogger(SignalAggregationWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cycleInterval = 60.minutes
    private val startupDelay = 45.seconds
    private val meiliBatchSize = 200
    private val signalWindowDays = 30L

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            try {
                runCycle()
            } catch (e: Exception) {
                log.error("Signal aggregation cycle failed", e)
            }
            delay(cycleInterval)
        }
    }

    suspend fun runCycle() {
        val started = System.currentTimeMillis()
        val signalRows = aggregateEvents()
        val scores = computeSearchScores(signalRows)
        pushToMeilisearch(scores)
        val elapsed = System.currentTimeMillis() - started
        log.info(
            "SignalAggregation cycle: {} repos with signals, {} total repos scored, {} ms",
            signalRows.size, scores.size, elapsed,
        )
    }

    private fun aggregateEvents(): Map<Long, SignalRow> {
        val cutoff = OffsetDateTime.now().minusDays(signalWindowDays)
        val rows = mutableMapOf<Long, SignalRow>()

        transaction {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            // INNER JOIN with repos so orphan event.repo_ids (clients can log clicks
            // for any GitHub repo, not just ones the backend indexed) don't violate
            // the repo_signals.repo_id FK.
            conn.prepareStatement(
                """
                SELECT
                    e.repo_id,
                    COUNT(*) FILTER (WHERE e.event_type = 'search_result_clicked') AS clicks,
                    COUNT(*) FILTER (WHERE e.event_type = 'repo_viewed')           AS views,
                    COUNT(*) FILTER (WHERE e.event_type = 'install_started')       AS installs_started,
                    COUNT(*) FILTER (WHERE e.event_type = 'install_succeeded')     AS installs_success,
                    COUNT(*) FILTER (WHERE e.event_type = 'install_failed')        AS installs_failed,
                    MAX(e.ts) FILTER (WHERE e.event_type = 'search_result_clicked')  AS last_click
                FROM events e
                INNER JOIN repos r ON e.repo_id = r.id
                WHERE e.ts > ? AND e.repo_id IS NOT NULL
                GROUP BY e.repo_id
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, cutoff)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val repoId = rs.getLong("repo_id")
                        val clicks = rs.getInt("clicks")
                        val views = rs.getInt("views")
                        val installsStarted = rs.getInt("installs_started")
                        val installsSuccess = rs.getInt("installs_success")
                        val installsFailed = rs.getInt("installs_failed")
                        val lastClick = rs.getTimestamp("last_click")?.toInstant()?.atOffset(OffsetDateTime.now().offset)
                        rows[repoId] = SignalRow(
                            repoId, clicks, views, installsStarted, installsSuccess, installsFailed, lastClick,
                        )
                    }
                }
            }

            // Upsert each aggregate into repo_signals.
            val now = OffsetDateTime.now()
            conn.prepareStatement(
                """
                INSERT INTO repo_signals (
                    repo_id, click_count_30d, view_count_30d,
                    install_started_30d, install_success_30d, install_failed_30d,
                    ctr_score, install_success_rate, last_click_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (repo_id) DO UPDATE SET
                    click_count_30d      = EXCLUDED.click_count_30d,
                    view_count_30d       = EXCLUDED.view_count_30d,
                    install_started_30d  = EXCLUDED.install_started_30d,
                    install_success_30d  = EXCLUDED.install_success_30d,
                    install_failed_30d   = EXCLUDED.install_failed_30d,
                    ctr_score            = EXCLUDED.ctr_score,
                    install_success_rate = EXCLUDED.install_success_rate,
                    last_click_at        = EXCLUDED.last_click_at,
                    updated_at           = EXCLUDED.updated_at
                """.trimIndent()
            ).use { ps ->
                for (row in rows.values) {
                    val ctr = laplaceCtr(row.clicks, row.views)
                    val installRate = laplaceInstallRate(row.installsSuccess, row.installsFailed)
                    ps.setLong(1, row.repoId)
                    ps.setInt(2, row.clicks)
                    ps.setInt(3, row.views)
                    ps.setInt(4, row.installsStarted)
                    ps.setInt(5, row.installsSuccess)
                    ps.setInt(6, row.installsFailed)
                    ps.setFloat(7, ctr)
                    ps.setFloat(8, installRate)
                    if (row.lastClickAt != null) ps.setObject(9, row.lastClickAt) else ps.setObject(9, null)
                    ps.setObject(10, now)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return rows
    }

    private fun computeSearchScores(signals: Map<Long, SignalRow>): List<ScoredRepo> {
        val scored = mutableListOf<ScoredRepo>()
        transaction {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            // Left-join every repo with its aggregate; unscored repos still get a baseline.
            conn.prepareStatement(
                """
                SELECT
                    r.id,
                    r.stars,
                    EXTRACT(EPOCH FROM (NOW() - r.latest_release_date)) / 86400.0 AS days_since_release
                FROM repos r
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong("id")
                        val stars = rs.getInt("stars")
                        val daysRaw = rs.getObject("days_since_release") as? Number
                        val days = daysRaw?.toDouble()
                        val sig = signals[id]
                        val ctr = sig?.let { laplaceCtr(it.clicks, it.views) } ?: 0f
                        val installRate = sig?.let { laplaceInstallRate(it.installsSuccess, it.installsFailed) } ?: 0f
                        val score = SearchScore.compute(stars, ctr.toDouble(), installRate.toDouble(), days)
                        scored.add(ScoredRepo(id, score))
                    }
                }
            }

            conn.prepareStatement("UPDATE repos SET search_score = ? WHERE id = ?").use { ps ->
                for (s in scored) {
                    ps.setFloat(1, s.score.toFloat())
                    ps.setLong(2, s.id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return scored
    }

    private suspend fun pushToMeilisearch(scores: List<ScoredRepo>) {
        scores.chunked(meiliBatchSize).forEach { batch ->
            val updates = batch.map { MeiliScoreUpdate(it.id, it.score) }
            try {
                meilisearchClient.updateScores(updates)
            } catch (e: Exception) {
                log.warn("Failed to push score batch of ${batch.size} to Meilisearch", e)
            }
        }
    }

    private fun laplaceCtr(clicks: Int, views: Int): Float {
        // +1/+2 Laplace: treats a repo with 0 views as 0.5 prior, then updates.
        return ((clicks + 1).toFloat() / (views + 2).toFloat()).coerceIn(0f, 1f)
    }

    private fun laplaceInstallRate(success: Int, failed: Int): Float {
        return ((success + 1).toFloat() / (success + failed + 2).toFloat()).coerceIn(0f, 1f)
    }

    private data class SignalRow(
        val repoId: Long,
        val clicks: Int,
        val views: Int,
        val installsStarted: Int,
        val installsSuccess: Int,
        val installsFailed: Int,
        val lastClickAt: OffsetDateTime?,
    )

    private data class ScoredRepo(val id: Long, val score: Double)
}
