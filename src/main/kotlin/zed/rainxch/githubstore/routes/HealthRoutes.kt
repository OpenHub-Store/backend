package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.model.HealthResponse

fun Route.healthRoutes() {
    get("/health") {
        val postgresStatus = try {
            transaction { exec("SELECT 1") }
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }

        val status = if (postgresStatus == "ok") "healthy" else "degraded"
        val httpStatus = if (postgresStatus == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable

        call.respond(
            httpStatus,
            HealthResponse(
                status = status,
                postgres = postgresStatus,
                version = "0.1.0",
            )
        )
    }
}
