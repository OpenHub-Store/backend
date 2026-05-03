package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import zed.rainxch.githubstore.BuildInfo
import zed.rainxch.githubstore.announcements.AnnouncementsRegistry
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.model.HealthResponse

fun Route.healthRoutes(
    meilisearch: MeilisearchClient,
    announcements: AnnouncementsRegistry,
) = healthRoutes(
    announcements = announcements,
    meilisearchHealthy = { meilisearch.isHealthy() },
    postgresHealthy = { defaultPostgresHealthCheck() },
)

private suspend fun defaultPostgresHealthCheck(): String = try {
    newSuspendedTransaction(Dispatchers.IO) { exec("SELECT 1") }
    "ok"
} catch (e: Exception) {
    "error: ${e.message}"
}

fun Route.healthRoutes(
    announcements: AnnouncementsRegistry,
    // Decoupled probes so unit tests can pass fast lambdas without spinning
    // up a real CIO HttpClient or a JDBC pool.
    meilisearchHealthy: suspend () -> Boolean,
    postgresHealthy: suspend () -> String,
) {
    get("/health") {
        val postgresStatus = postgresHealthy()
        val meilisearchStatus = if (meilisearchHealthy()) "ok" else "unavailable"

        val allHealthy = postgresStatus == "ok" && meilisearchStatus == "ok"
        val status = if (postgresStatus == "ok") {
            if (allHealthy) "healthy" else "degraded"
        } else "unhealthy"
        val httpStatus = if (postgresStatus == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable

        call.respond(
            httpStatus,
            HealthResponse(
                status = status,
                postgres = postgresStatus,
                meilisearch = meilisearchStatus,
                version = BuildInfo.version,
                announcements = announcements.loadedCount(),
            )
        )
    }
}
