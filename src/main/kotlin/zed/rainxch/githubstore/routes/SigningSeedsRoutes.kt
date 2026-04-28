package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.match.SigningFingerprintRepository
import zed.rainxch.githubstore.match.SigningSeedsResponse
import zed.rainxch.githubstore.util.ApiError

private const val DEFAULT_PAGE_SIZE = 1000
private const val MAX_PAGE_SIZE = 5000

// Per E1_BACKEND_HANDOFF.md:
//   GET /v1/signing-seeds?since=<epoch_ms>&platform=android&cursor=<opaque>
//
// Anonymous, paginated. observedAt is epoch milliseconds -- never seconds.
// `platform` is required and currently only `android` is meaningful for E1.
fun Route.signingSeedsRoutes(repository: SigningFingerprintRepository) {
    get("/signing-seeds") {
        val platform = call.request.queryParameters["platform"]
        if (platform != "android") {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_platform", message = "platform query parameter required; android is the only supported value for now"),
            )
            return@get
        }

        val since = call.request.queryParameters["since"]?.let { raw ->
            raw.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_since", message = "since must be epoch milliseconds"))
                return@get
            }
        }
        if (since != null && since < 0) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_since", message = "since must be non-negative"))
            return@get
        }

        val cursor = call.request.queryParameters["cursor"]?.let { token ->
            SigningFingerprintRepository.PageCursor.decode(token) ?: run {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_cursor"))
                return@get
            }
        }

        val pageSize = call.request.queryParameters["limit"]?.toIntOrNull()
            ?.coerceIn(1, MAX_PAGE_SIZE)
            ?: DEFAULT_PAGE_SIZE

        val page = repository.page(sinceMs = since, cursor = cursor, limit = pageSize)
        val response = SigningSeedsResponse(
            rows = page.rows,
            nextCursor = page.nextCursor?.encode(),
        )

        // Paginated dump -- clients fetch incrementally with their own `since`
        // cursor, so a short edge cache is fine. 5 minutes balances freshness
        // (new F-Droid index data lands within minutes of a daily cron run)
        // against repeat fetches from the same client during a sync session.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
        call.respond(response)
    }
}
