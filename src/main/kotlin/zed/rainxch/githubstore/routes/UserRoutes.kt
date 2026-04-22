package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.ingest.GitHubResourceClient

fun Route.userRoutes(resourceClient: GitHubResourceClient) {
    get("/user/{username}") {
        val username = call.parameters["username"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing username"))

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        // GitHub user/org profiles rarely change meaningfully. Long TTL +
        // aggressive edge caching means a popular maintainer's profile
        // (e.g., `sindresorhus`) gets fetched once a week per backend and
        // served from Gcore for almost every hit.
        val cacheKey = "user:$username"
        val upstreamUrl = "https://api.github.com/users/$username"
        val ttlSeconds = 7L * 86_400L // 7 days

        val result = resourceClient.fetchCached(
            cacheKey = cacheKey,
            upstreamUrl = upstreamUrl,
            userToken = userToken,
            ttlSeconds = ttlSeconds,
        )

        when (result) {
            is GitHubResourceClient.Result.Hit -> {
                // 1 day browser / 7 day edge — Gcore absorbs nearly all hits.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=86400, s-maxage=604800")
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
