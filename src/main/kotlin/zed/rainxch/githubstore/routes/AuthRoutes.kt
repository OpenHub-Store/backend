package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.ingest.GitHubDeviceClient
import zed.rainxch.githubstore.requireMaxBody
import zed.rainxch.githubstore.util.ApiError

private val log = LoggerFactory.getLogger("AuthRoutes")

private const val START_MAX_BODY = 1L * 1024
private const val POLL_MAX_BODY = 4L * 1024

fun Route.authRoutes(deviceClient: GitHubDeviceClient) {
    route("/auth/device") {
        rateLimit(RateLimitName("auth-start")) {
            post("/start") {
                if (!call.requireMaxBody(START_MAX_BODY)) return@post
                try {
                    val result = deviceClient.startDeviceFlow()
                    val outStatus = if (result.status.isSuccess()) {
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.BadGateway
                    }
                    call.respondText(
                        text = result.body,
                        contentType = ContentType.Application.Json,
                        status = outStatus,
                    )
                } catch (e: Exception) {
                    log.warn("auth/device/start upstream error: {}", e.message)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiError("github_unreachable"),
                    )
                }
            }
        }

        rateLimit(RateLimitName("auth-poll")) {
            post("/poll") {
                if (!call.requireMaxBody(POLL_MAX_BODY)) return@post
                val form = try {
                    call.receiveParameters()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_body"),
                    )
                }
                val deviceCode = form["device_code"]?.takeIf { it.isNotBlank() }
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("missing_device_code"),
                    )

                try {
                    val result = deviceClient.pollDeviceToken(deviceCode)
                    // Device-flow pending/error states (authorization_pending,
                    // slow_down, access_denied, expired_token, ...) arrive from
                    // GitHub as HTTP 200 with an error-shaped body. Forward
                    // 200→200 verbatim; the client already string-matches these.
                    // Only non-2xx flips to 502 so the client's infrastructure
                    // fallback predicate fires cleanly.
                    val outStatus = if (result.status.isSuccess()) {
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.BadGateway
                    }
                    call.respondText(
                        text = result.body,
                        contentType = ContentType.Application.Json,
                        status = outStatus,
                    )
                } catch (e: Exception) {
                    log.warn("auth/device/poll upstream error: {}", e.message)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiError("github_unreachable"),
                    )
                }
            }
        }
    }
}
