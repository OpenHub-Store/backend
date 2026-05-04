package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.util.GitHubIdentifiers

// Whitelisted to keep query-string-injected SSRF off the upstream URL.
// GitHub silently ignores unknown values; we reject so a typo doesn't return
// the wrong-shaped data quietly.
private val VALID_TYPES = setOf("all", "owner", "member")
private val VALID_SORTS = setOf("created", "updated", "pushed", "full_name")
private val VALID_DIRECTIONS = setOf("asc", "desc")

// GET /v1/users/{username}/repos -- public passthrough for a user/org's
// repo list. Powers the developer profile screen. Heavily paginated and
// per-(username,page,sort,type) cached because the underlying answer
// rarely changes within an hour.
fun Route.userReposRoutes(resourceClient: GitHubResourceClient) {
    get("/users/{username}/repos") {
        val username = GitHubIdentifiers.validOwner(call.parameters["username"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_owner"))

        val type = call.request.queryParameters["type"]?.takeIf { it in VALID_TYPES } ?: "owner"
        val sort = call.request.queryParameters["sort"]?.takeIf { it in VALID_SORTS } ?: "updated"
        val direction = call.request.queryParameters["direction"]?.takeIf { it in VALID_DIRECTIONS } ?: "desc"
        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, 50)
        val perPage = (call.request.queryParameters["per_page"]?.toIntOrNull() ?: 30).coerceIn(1, 100)

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        val cacheKey = "user-repos:$username?type=$type&sort=$sort&dir=$direction&page=$page&pp=$perPage"
        val upstreamUrl =
            "https://api.github.com/users/$username/repos" +
                "?type=$type&sort=$sort&direction=$direction&per_page=$perPage&page=$page"

        // 1h TTL: balance between "user pushes a new repo, profile screen
        // needs to reflect within reason" and "the profile reload button
        // shouldn't smash GitHub". Aligns with /releases TTL.
        val ttlSeconds = 3_600L

        val result = resourceClient.fetchCached(
            cacheKey = cacheKey,
            upstreamUrl = upstreamUrl,
            userToken = userToken,
            ttlSeconds = ttlSeconds,
        )

        when (result) {
            is GitHubResourceClient.Result.Hit -> {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=300, s-maxage=1800")
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
