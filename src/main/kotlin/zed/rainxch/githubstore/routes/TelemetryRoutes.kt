package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.telemetry.PropsSchema
import zed.rainxch.githubstore.telemetry.TelemetryAllowlist
import zed.rainxch.githubstore.telemetry.TelemetryBatchRequest
import zed.rainxch.githubstore.telemetry.TelemetryEvent
import zed.rainxch.githubstore.telemetry.TelemetryJson
import zed.rainxch.githubstore.telemetry.TelemetryQueue

private val log = LoggerFactory.getLogger("TelemetryRoutes")

private const val MAX_BATCH_SIZE = 100
private const val MAX_SESSION_ID_LEN = 128
private const val MAX_PLATFORM_LEN = 32
private const val MAX_APP_VERSION_LEN = 32
private const val MAX_NAME_LEN = 64
private const val MAX_BODY_BYTES = 256 * 1024L
private const val MAX_PROPS_BYTES = 2048

fun Route.telemetryRoutes(queue: TelemetryQueue) {
    post("/telemetry/events") {
        // Pre-check before receive() so a megabyte body never gets buffered into
        // the JVM heap. Missing Content-Length is treated as oversized — we don't
        // accept chunked uploads for telemetry.
        val contentLength = call.request.contentLength()
        if (contentLength == null || contentLength > MAX_BODY_BYTES) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to "payload_too_large"),
            )
            return@post
        }

        val body = call.receive<TelemetryBatchRequest>()

        if (body.events.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        if (body.events.size > MAX_BATCH_SIZE) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "batch_too_large"),
            )
            return@post
        }

        if (body.events.any { it.name.isBlank() }) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "invalid_event_name"),
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
                mapOf("error" to "field_too_long"),
            )
            return@post
        }

        // Per-event props size cap. Bound applies AFTER serialization (post-
        // canonicalization), pre-strip, so a client can't sneak past it by
        // padding allowed keys.
        val propsTooBig = body.events.firstOrNull { e ->
            e.props?.let { TelemetryJson.encodeToString(JsonObject.serializer(), it).encodeToByteArray().size > MAX_PROPS_BYTES } == true
        }
        if (propsTooBig != null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "props_too_large"),
            )
            return@post
        }

        val nameAccepted = body.events.filter { it.name in TelemetryAllowlist.EVENTS }
        val nameDropped = body.events.size - nameAccepted.size
        if (nameDropped > 0) {
            // INFO not WARN — clients on stale builds will trigger this normally
            // when the schema evolves. Operator dashboards can graph the rate.
            log.info("Telemetry: dropped {} non-allowlisted events of {} submitted", nameDropped, body.events.size)
        }

        var strippedKeyCount = 0
        val accepted: List<TelemetryEvent> = nameAccepted.map { e ->
            val props = e.props ?: return@map e
            val allowed = PropsSchema.allowedKeys(e.name)
            val filtered = props.filterKeys { it in allowed }
            if (filtered.size == props.size) {
                e
            } else {
                strippedKeyCount += props.size - filtered.size
                e.copy(props = JsonObject(filtered))
            }
        }
        if (strippedKeyCount > 0) {
            log.info("Telemetry: stripped {} disallowed prop keys", strippedKeyCount)
        }

        if (accepted.isNotEmpty()) {
            queue.submit(accepted)
        }

        // 204 only when every submitted event was accepted with no key
        // stripping. Otherwise return 200 + counts so clients can detect
        // schema drift without reading the body.
        if (nameDropped == 0 && strippedKeyCount == 0) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(
                HttpStatusCode.OK,
                mapOf("accepted" to accepted.size, "dropped" to nameDropped),
            )
        }
    }
}
