package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val BASIC_AUTH_REALM = "github-store-admin"
const val ADMIN_BASIC_AUTH = "admin-basic"

fun Route.internalRoutes(metrics: SearchMetricsRegistry, workerSupervisor: WorkerSupervisor) {
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
        // JSON metrics — accepts either Basic Auth (so the dashboard's fetch()
        // carries the browser's cached credentials) OR an X-Admin-Token header
        // (for curl / machine callers). optional=true makes the authenticate
        // block parse credentials when present but not require them; the
        // authorized() helper below is the actual gate.
        authenticate(ADMIN_BASIC_AUTH, optional = true) {
            get("/metrics") {
                if (!authorized(call, adminToken)) {
                    return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
                }
                // An authenticated endpoint must never be edge-cached.
                call.response.header(HttpHeaders.CacheControl, "no-store, private")
                val snap = metrics.snapshot()
                val counters = SearchCounters(
                    uptimeSeconds = snap.uptimeSeconds,
                    meiliOnly = snap.meiliOnly,
                    passthrough = snap.passthrough,
                    postgresFallback = snap.postgresFallback,
                    explore = snap.explore,
                    zeroResult = snap.zeroResult,
                    avgLatencyMs = snap.avgLatencyMs,
                )
                val (db, top) = coroutineScope {
                    val dbAsync = async { fetchDbMetrics() }
                    val topAsync = async { fetchTopRepos() }
                    dbAsync.await() to topAsync.await()
                }
                val workers = workerSupervisor.lastTicks().mapValues { it.value.toString() }
                call.respond(MetricsResponse(
                    counters = counters,
                    training = db,
                    topRepos = top,
                    workers = workers,
                ))
            }
        }

        // Browser dashboard. Basic Auth required in prod so the browser prompts
        // for credentials on first visit; optional in dev for local inspection.
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
    // Constant-time compare to defang token-length / per-byte timing oracles.
    // MessageDigest.isEqual NPEs on null inputs, so guard first.
    if (header != null && MessageDigest.isEqual(
            header.toByteArray(StandardCharsets.UTF_8),
            adminToken.toByteArray(StandardCharsets.UTF_8),
        )) return true
    val principal = call.principal<UserIdPrincipal>()
    return principal != null
}

private suspend fun fetchDbMetrics(): TrainingMetrics = coroutineScope {
    val unprocessed = async { countUnprocessedMisses() }
    val reposWithSignals = async { countReposWithSignals() }
    val reposWithSearchScore = async { countReposWithSearchScore() }
    val topMisses = async { fetchTopMisses() }
    TrainingMetrics(
        unprocessedMisses = unprocessed.await(),
        reposWithSignals = reposWithSignals.await(),
        reposWithSearchScore = reposWithSearchScore.await(),
        topMissesLast7d = topMisses.await(),
    )
}

private suspend fun countUnprocessedMisses(): Long = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    conn.prepareStatement(
        "SELECT COUNT(*) FROM search_misses WHERE last_processed_at IS NULL"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

private suspend fun countReposWithSignals(): Long = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    conn.prepareStatement(
        "SELECT COUNT(*) FROM repo_signals"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

private suspend fun countReposWithSearchScore(): Long = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    conn.prepareStatement(
        "SELECT COUNT(*) FROM repos WHERE search_score IS NOT NULL"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

// Top misses: report only the hash-prefix and counts. Raw query text is
// no longer stored (privacy hardening, V6) so the dashboard surfaces an
// 8-char identifier per query — useful for spotting hotspots without
// exposing what users typed.
private suspend fun fetchTopMisses(): List<TopMiss> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopMiss>()
    conn.prepareStatement(
        """
        SELECT query_hash, miss_count, result_count, last_seen_at
        FROM search_misses
        WHERE last_seen_at > NOW() - INTERVAL '7 days'
        ORDER BY miss_count DESC
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                out.add(
                    TopMiss(
                        query = rs.getString("query_hash")?.take(8) ?: "—",
                        missCount = rs.getInt("miss_count"),
                        resultCount = rs.getObject("result_count") as? Int,
                        lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant()?.toString(),
                    )
                )
            }
        }
    }
    out
}

private suspend fun fetchTopRepos(): TopRepos = coroutineScope {
    val byScore = async { fetchTopReposByScore() }
    val byClicks = async { fetchTopReposByClicks() }
    val byInstalls = async { fetchTopReposByInstalls() }
    TopRepos(
        byScore = byScore.await(),
        byClicks = byClicks.await(),
        byInstalls = byInstalls.await(),
    )
}

private suspend fun fetchTopReposByScore(): List<TopRepo> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopRepo>()
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
                out.add(
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
    out
}

private suspend fun fetchTopReposByClicks(): List<TopRepo> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopRepo>()
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
                out.add(
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
    out
}

private suspend fun fetchTopReposByInstalls(): List<TopRepo> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopRepo>()
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
                out.add(
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
    out
}

@Serializable
data class MetricsResponse(
    val counters: SearchCounters,
    val training: TrainingMetrics,
    val topRepos: TopRepos = TopRepos(),
    val workers: Map<String, String> = emptyMap(),
)

@Serializable
data class SearchCounters(
    val uptimeSeconds: Long,
    val meiliOnly: Long,
    val passthrough: Long,
    val postgresFallback: Long,
    val explore: Long,
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
