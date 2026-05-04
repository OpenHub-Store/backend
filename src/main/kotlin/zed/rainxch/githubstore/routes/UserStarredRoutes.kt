package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.util.GitHubIdentifiers

// Whitelist sort/direction the same way userReposRoutes does -- prevents
// query-string-injected SSRF and silent default-fallback bugs.
private val VALID_STARRED_SORTS = setOf("created", "updated")
private val VALID_STARRED_DIRECTIONS = setOf("asc", "desc")

// GET /v1/users/{username}/starred -- public passthrough for a user's
// starred repos. Powers the starred-repos picker on the profile screen.
//
// Only the /users/{username}/starred form is supported here; the
// authenticated /user/starred form (current viewer's stars) is OAuth-bound
// and would require a separate flow that wires X-GitHub-Token through
// without caching cross-user. Out of scope for this endpoint -- callers
// who want the viewer's own stars must call GitHub directly.
fun Route.userStarredRoutes(resourceClient: GitHubResourceClient) {
    get("/users/{username}/starred") {
        val username = GitHubIdentifiers.validOwner(call.parameters["username"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_owner"))

        val sort = call.request.queryParameters["sort"]?.takeIf { it in VALID_STARRED_SORTS } ?: "created"
        val direction = call.request.queryParameters["direction"]?.takeIf { it in VALID_STARRED_DIRECTIONS } ?: "desc"
        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, 50)
        val perPage = (call.request.queryParameters["per_page"]?.toIntOrNull() ?: 30).coerceIn(1, 100)

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        val cacheKey = "user-starred:$username?sort=$sort&dir=$direction&page=$page&pp=$perPage"
        val upstreamUrl =
            "https://api.github.com/users/$username/starred" +
                "?sort=$sort&direction=$direction&per_page=$perPage&page=$page"

        // 30min TTL: stars churn faster than repos for active users (a star
        // takes 1 click). Edge cache keeps the picker UI snappy without
        // staleness becoming visible during a session.
        val ttlSeconds = 1_800L

        val result = resourceClient.fetchCached(
            cacheKey = cacheKey,
            upstreamUrl = upstreamUrl,
            userToken = userToken,
            ttlSeconds = ttlSeconds,
        )

        when (result) {
            is GitHubResourceClient.Result.Hit -> {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=180, s-maxage=900")
                call.respondText(result.body, ContentType.parse(result.contentType), HttpStatusCode.OK)
            }
            is GitHubResourceClient.Result.StaleFallback -> {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Cache-State", "stale-fallback")
                call.respondText(result.body, ContentType.parse(result.contentType), HttpStatusCode.OK)
            }
            is GitHubResourceClient.Result.NegativeHit -> {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
                call.respond(HttpStatusCode.fromValue(result.status), mapOf("error" to "upstream_${result.status}"))
            }
            is GitHubResourceClient.Result.UpstreamError -> {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "github_unreachable"))
            }
        }
    }
}
