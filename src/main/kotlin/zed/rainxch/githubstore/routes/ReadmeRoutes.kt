package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.ingest.GitHubResourceClient

fun Route.readmeRoutes(resourceClient: GitHubResourceClient) {
    get("/readme/{owner}/{name}") {
        val owner = call.parameters["owner"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing owner"))
        val name = call.parameters["name"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing name"))

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        // GitHub's /readme returns a JSON object with base64-encoded content
        // + metadata (size, html_url, download_url, encoding). Forwarding the
        // JSON shape verbatim keeps the client's parsing path unchanged — the
        // client was already calling github.com/.../readme directly and
        // decoding the base64 locally.
        val cacheKey = "readme:$owner/$name"
        val upstreamUrl = "https://api.github.com/repos/$owner/$name/readme"
        val ttlSeconds = 86_400L // 24h — READMEs change far less often than releases

        val result = resourceClient.fetchCached(
            cacheKey = cacheKey,
            upstreamUrl = upstreamUrl,
            userToken = userToken,
            ttlSeconds = ttlSeconds,
        )

        when (result) {
            is GitHubResourceClient.Result.Hit -> {
                // 1h browser / 6h edge — aggressive because READMEs barely change.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=3600, s-maxage=21600")
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
