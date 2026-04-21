package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry

private const val BASIC_AUTH_REALM = "github-store-admin"
const val ADMIN_BASIC_AUTH = "admin-basic"

fun Route.internalRoutes(metrics: SearchMetricsRegistry) {
    val adminToken: String? = System.getenv("ADMIN_TOKEN")?.takeIf { it.isNotBlank() }
    val isProduction = System.getenv("APP_ENV") == "production"

    // Fail-closed under prod when ADMIN_TOKEN is missing (see the authenticate
    // setup in Plugins.kt — the provider returns null-credentials which Ktor
    // treats as unauthenticated, which `authenticate { }` rejects with 401).
    // The extra guard here is belt-and-suspenders: even if Plugins.kt gets
    // mis-edited, the internal routes never register without a token.
    if (isProduction && adminToken == null) {
        route("/internal") {
            get("{...}") {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
            }
        }
        return
    }

    route("/internal") {
        // JSON metrics — gated by X-Admin-Token header (machine use) OR Basic
        // Auth (browser / dashboard use). Dev-mode (adminToken null) stays open.
        get("/metrics") {
            if (!authorized(call, adminToken)) {
                return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
            }
            // An authenticated endpoint must never be edge-cached — or a pre-hardening
            // response can linger at the CDN and be served to anyone for the cache TTL.
            call.response.header(HttpHeaders.CacheControl, "no-store, private")
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
            val top = fetchTopRepos()
            call.respond(MetricsResponse(counters = counters, training = db, topRepos = top))
        }

        // Browser dashboard. Basic Auth only (tokens in headers aren't
        // UX-friendly from a browser).
        authenticate(ADMIN_BASIC_AUTH, optional = adminToken == null) {
            get("/dashboard") {
                val html = {}.javaClass.classLoader
                    .getResourceAsStream("admin/dashboard.html")
                    ?.bufferedReader()?.readText()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Robots-Tag", "noindex, nofollow")
                call.respondText(html, ContentType.Text.Html)
            }
        }
    }
}

// Returns true if the caller is allowed to see JSON metrics. Three paths:
//   - Dev (adminToken == null): always allowed.
//   - Header X-Admin-Token matches: allowed.
//   - Authenticated via Basic Auth (browser reloading the /metrics fetch with
//     cached credentials): allowed.
private fun authorized(call: io.ktor.server.application.ApplicationCall, adminToken: String?): Boolean {
    if (adminToken == null) return true
    val header = call.request.headers["X-Admin-Token"]
    if (header == adminToken) return true
    val principal = call.principal<UserIdPrincipal>()
    return principal != null
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

private fun fetchTopRepos(): TopRepos = transaction {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection

    val byScore = mutableListOf<TopRepo>()
    conn.prepareStatement(
        """
        SELECT id, full_name, stars, search_score
        FROM repos
        WHERE search_score IS NOT NULL
        ORDER BY search_score DESC NULLS LAST
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                byScore.add(
                    TopRepo(
                        id = rs.getLong("id"),
                        fullName = rs.getString("full_name"),
                        value = (rs.getObject("search_score") as? Number)?.toDouble() ?: 0.0,
                        stars = rs.getInt("stars"),
                    )
                )
            }
        }
    }

    val byClicks = mutableListOf<TopRepo>()
    conn.prepareStatement(
        """
        SELECT r.id, r.full_name, r.stars, s.click_count_30d AS v
        FROM repo_signals s
        INNER JOIN repos r ON r.id = s.repo_id
        WHERE s.click_count_30d > 0
        ORDER BY s.click_count_30d DESC
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                byClicks.add(
                    TopRepo(
                        id = rs.getLong("id"),
                        fullName = rs.getString("full_name"),
                        value = rs.getInt("v").toDouble(),
                        stars = rs.getInt("stars"),
                    )
                )
            }
        }
    }

    val byInstalls = mutableListOf<TopRepo>()
    conn.prepareStatement(
        """
        SELECT r.id, r.full_name, r.stars, s.install_success_30d AS v
        FROM repo_signals s
        INNER JOIN repos r ON r.id = s.repo_id
        WHERE s.install_success_30d > 0
        ORDER BY s.install_success_30d DESC
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                byInstalls.add(
                    TopRepo(
                        id = rs.getLong("id"),
                        fullName = rs.getString("full_name"),
                        value = rs.getInt("v").toDouble(),
                        stars = rs.getInt("stars"),
                    )
                )
            }
        }
    }

    TopRepos(byScore = byScore, byClicks = byClicks, byInstalls = byInstalls)
}

@Serializable
data class MetricsResponse(
    val counters: SearchCounters,
    val training: TrainingMetrics,
    val topRepos: TopRepos = TopRepos(),
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

@Serializable
data class TopRepos(
    val byScore: List<TopRepo> = emptyList(),
    val byClicks: List<TopRepo> = emptyList(),
    val byInstalls: List<TopRepo> = emptyList(),
)

@Serializable
data class TopRepo(
    val id: Long,
    val fullName: String,
    val value: Double,
    val stars: Int,
)
