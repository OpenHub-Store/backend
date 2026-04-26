package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.telemetry.TelemetryAllowlist
import zed.rainxch.githubstore.telemetry.TelemetryBatchRequest
import zed.rainxch.githubstore.telemetry.TelemetryRepository

private val log = LoggerFactory.getLogger("TelemetryRoutes")

private const val MAX_BATCH_SIZE = 100
private const val MAX_SESSION_ID_LEN = 128
private const val MAX_PLATFORM_LEN = 32
private const val MAX_APP_VERSION_LEN = 32
private const val MAX_NAME_LEN = 64

fun Route.telemetryRoutes(repository: TelemetryRepository) {
    post("/telemetry/events") {
        val body = call.receive<TelemetryBatchRequest>()

        if (body.events.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        if (body.events.size > MAX_BATCH_SIZE) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "max $MAX_BATCH_SIZE events per batch"),
            )
            return@post
        }

        // Per-event field-length cap. Defends against a buggy client (or
        // forgery) pushing megabyte strings into the table — telemetry rows
        // never get GC'd in Week 1 so unbounded growth has lasting cost.
        val oversized = body.events.firstOrNull { e ->
            e.name.length > MAX_NAME_LEN ||
                e.sessionId.length > MAX_SESSION_ID_LEN ||
                (e.platform?.length ?: 0) > MAX_PLATFORM_LEN ||
                (e.appVersion?.length ?: 0) > MAX_APP_VERSION_LEN
        }
        if (oversized != null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "field too long"),
            )
            return@post
        }

        val accepted = body.events.filter { it.name in TelemetryAllowlist.EVENTS }
        val dropped = body.events.size - accepted.size
        if (dropped > 0) {
            // INFO not WARN — clients on stale builds will trigger this normally
            // when the schema evolves. Operator dashboards can graph the rate.
            log.info("Telemetry: dropped {} non-allowlisted events of {} submitted", dropped, body.events.size)
        }

        if (accepted.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                repository.insertBatch(accepted)
            }
        }

        call.respond(HttpStatusCode.NoContent)
    }
}
