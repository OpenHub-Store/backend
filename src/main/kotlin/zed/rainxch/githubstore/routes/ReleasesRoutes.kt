package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.ingest.GitHubResourceClient

fun Route.releasesRoutes(resourceClient: GitHubResourceClient) {
    get("/releases/{owner}/{name}") {
        val owner = call.parameters["owner"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing owner"))
        val name = call.parameters["name"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing name"))

        // Allow client to ask for a specific page — cache key includes it so
        // page 1 and page 2 don't collide in the cache.
        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, 50)
        val perPage = (call.request.queryParameters["per_page"]?.toIntOrNull() ?: 100).coerceIn(1, 100)

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        val cacheKey = "releases:$owner/$name?page=$page&per_page=$perPage"
        val upstreamUrl =
            "https://api.github.com/repos/$owner/$name/releases?per_page=$perPage&page=$page"

        val ttlSeconds = 3600L // 1 hour — releases publish frequently on active repos

        val result = resourceClient.fetchCached(
            cacheKey = cacheKey,
            upstreamUrl = upstreamUrl,
            userToken = userToken,
            ttlSeconds = ttlSeconds,
        )

        when (result) {
            is GitHubResourceClient.Result.Hit -> {
                // Edge-cache 60s; client already TTLs 6h locally so repeat
                // asks within an hour just hit Gcore.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=30, s-maxage=60")
                call.respondText(result.body, ContentType.parse(result.contentType), HttpStatusCode.OK)
            }
            is GitHubResourceClient.Result.StaleFallback -> {
                // Serving stale because upstream is unreachable right now.
                // Tell intermediaries not to re-cache the stale answer.
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
