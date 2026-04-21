package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.model.EventRequest

private const val MAX_BATCH_SIZE = 50

// Per-field length caps. Events feeds the training pipeline and goes into
// the `events` table forever — without these, a buggy or malicious client
// can push multi-megabyte strings in and bloat the table.
private const val MAX_DEVICE_ID_LEN = 128
private const val MAX_APP_VERSION_LEN = 32
private const val MAX_ERROR_CODE_LEN = 128
private const val MAX_QUERY_HASH_LEN = 64

private val VALID_EVENT_TYPES = setOf(
    "search_performed",
    "search_result_clicked",
    "repo_viewed",
    "release_downloaded",
    "install_started",
    "install_succeeded",
    "install_failed",
    "app_opened_after_install",
    "uninstalled",
    "favorited",
    "unfavorited",
)

fun Route.eventRoutes(eventRepository: EventRepository) {
    post("/events") {
        val events = call.receive<List<EventRequest>>()

        if (events.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Empty event list"))
            return@post
        }

        if (events.size > MAX_BATCH_SIZE) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Max $MAX_BATCH_SIZE events per batch"))
            return@post
        }

        val invalid = events.filter { it.eventType !in VALID_EVENT_TYPES }
        if (invalid.isNotEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Unknown event types: ${invalid.map { it.eventType }.distinct()}")
            )
            return@post
        }

        val oversized = events.firstOrNull { e ->
            e.deviceId.length > MAX_DEVICE_ID_LEN ||
                (e.appVersion?.length ?: 0) > MAX_APP_VERSION_LEN ||
                (e.errorCode?.length ?: 0) > MAX_ERROR_CODE_LEN ||
                (e.queryHash?.length ?: 0) > MAX_QUERY_HASH_LEN
        }
        if (oversized != null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Field too long. Limits: deviceId $MAX_DEVICE_ID_LEN, appVersion $MAX_APP_VERSION_LEN, errorCode $MAX_ERROR_CODE_LEN, queryHash $MAX_QUERY_HASH_LEN chars"),
            )
            return@post
        }

        eventRepository.insertBatch(events)
        call.respond(HttpStatusCode.NoContent)
    }
}
