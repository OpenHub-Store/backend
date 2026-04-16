package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.ktor.ext.inject
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.model.HealthResponse

fun Route.healthRoutes() {
    val meilisearch by inject<MeilisearchClient>()

    get("/health") {
        val postgresStatus = try {
            transaction { exec("SELECT 1") }
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }

        val meilisearchStatus = if (meilisearch.isHealthy()) "ok" else "unavailable"

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
                version = "0.1.0",
            )
        )
    }
}
