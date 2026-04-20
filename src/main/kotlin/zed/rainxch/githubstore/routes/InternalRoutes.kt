package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry

/**
 * Operator-only visibility into the search + training pipeline. Gated by
 * `X-Admin-Token` matching the `ADMIN_TOKEN` env var. If the env var is unset
 * (local dev) the endpoint is open.
 */
fun Route.internalRoutes(metrics: SearchMetricsRegistry) {
    val adminToken: String? = System.getenv("ADMIN_TOKEN")?.takeIf { it.isNotBlank() }

    route("/internal") {
        get("/metrics") {
            val provided = call.request.headers["X-Admin-Token"]
            if (adminToken != null && provided != adminToken) {
                return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
            }

            val snap = metrics.snapshot()
            val counters = SearchCounters(
                uptimeSeconds = snap.uptimeSeconds,
                meiliOnly = snap.meiliOnly,
                passthrough = snap.passthrough,
                postgresFallback = snap.postgresFallback,
                zeroResult = snap.zeroResult,
                avgLatencyMs = snap.avgLatencyMs,
            )

            val db = fetchDbMetrics()
            call.respond(MetricsResponse(counters = counters, training = db))
        }
    }
}

private fun fetchDbMetrics(): TrainingMetrics = transaction {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection

    val unprocessed = conn.prepareStatement(
        "SELECT COUNT(*) FROM search_misses WHERE last_processed_at IS NULL"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }

    val reposWithSignals = conn.prepareStatement(
        "SELECT COUNT(*) FROM repo_signals"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }

    val reposWithSearchScore = conn.prepareStatement(
        "SELECT COUNT(*) FROM repos WHERE search_score IS NOT NULL"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }

    val topMisses = mutableListOf<TopMiss>()
    conn.prepareStatement(
        """
        SELECT query_sample, miss_count, result_count, last_seen_at
        FROM search_misses
        WHERE query_sample IS NOT NULL
          AND last_seen_at > NOW() - INTERVAL '7 days'
        ORDER BY miss_count DESC
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                topMisses.add(
                    TopMiss(
                        query = rs.getString("query_sample"),
                        missCount = rs.getInt("miss_count"),
                        resultCount = rs.getObject("result_count") as? Int,
                        lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant()?.toString(),
                    )
                )
            }
        }
    }

    TrainingMetrics(
        unprocessedMisses = unprocessed,
        reposWithSignals = reposWithSignals,
        reposWithSearchScore = reposWithSearchScore,
        topMissesLast7d = topMisses,
    )
}

@Serializable
data class MetricsResponse(
    val counters: SearchCounters,
    val training: TrainingMetrics,
)

@Serializable
data class SearchCounters(
    val uptimeSeconds: Long,
    val meiliOnly: Long,
    val passthrough: Long,
    val postgresFallback: Long,
    val zeroResult: Long,
    val avgLatencyMs: Long,
)

@Serializable
data class TrainingMetrics(
    val unprocessedMisses: Long,
    val reposWithSignals: Long,
    val reposWithSearchScore: Long,
    val topMissesLast7d: List<TopMiss>,
)

@Serializable
data class TopMiss(
    val query: String,
    val missCount: Int,
    val resultCount: Int?,
    val lastSeenAt: String?,
)
