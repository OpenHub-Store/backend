package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.model.EventRequest

private const val MAX_BATCH_SIZE = 50

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

        eventRepository.insertBatch(events)
        call.respond(HttpStatusCode.NoContent)
    }
}
