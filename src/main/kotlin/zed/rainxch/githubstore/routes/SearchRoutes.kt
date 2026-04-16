package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.model.SearchResponse

private val VALID_PLATFORMS = setOf("android", "windows", "macos", "linux")
private val VALID_SORTS = setOf("relevance", "stars", "recent")

fun Route.searchRoutes(meilisearch: MeilisearchClient, searchRepository: SearchRepository) {
    get("/search") {
        val query = call.request.queryParameters["q"]
        if (query.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing query parameter 'q'")
            )
        }

        val platform = call.request.queryParameters["platform"]
        if (platform != null && platform !in VALID_PLATFORMS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid platform. Must be one of: $VALID_PLATFORMS")
            )
        }

        val sort = call.request.queryParameters["sort"] ?: "relevance"
        if (sort !in VALID_SORTS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid sort. Must be one of: $VALID_SORTS")
            )
        }

        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

        // Try Meilisearch first, fall back to Postgres FTS
        try {
            val result = meilisearch.search(
                query = query,
                platform = platform,
                sort = sort,
                limit = limit,
                offset = offset,
            )

            val items = result.hits.map { hit ->
                RepoResponse(
                    id = hit.id,
                    name = hit.name,
                    fullName = hit.full_name,
                    owner = RepoOwner(login = hit.owner, avatarUrl = hit.owner_avatar_url),
                    description = hit.description,
                    defaultBranch = hit.default_branch,
                    htmlUrl = hit.html_url,
                    stargazersCount = hit.stars,
                    forksCount = hit.forks,
                    language = hit.language,
                    topics = emptyList(),
                    releasesUrl = "${hit.html_url}/releases",
                    updatedAt = null,
                    createdAt = null,
                    latestReleaseDate = hit.latest_release_date,
                    latestReleaseTag = hit.latest_release_tag,
                    hasInstallersAndroid = hit.has_installers_android,
                    hasInstallersWindows = hit.has_installers_windows,
                    hasInstallersMacos = hit.has_installers_macos,
                    hasInstallersLinux = hit.has_installers_linux,
                    trendingScore = hit.trending_score,
                    popularityScore = hit.popularity_score,
                )
            }

            call.respond(SearchResponse(
                items = items,
                totalHits = result.estimatedTotalHits,
                processingTimeMs = result.processingTimeMs,
                source = "meilisearch",
            ))
        } catch (e: Exception) {
            // Meilisearch down — fall back to Postgres FTS
            call.application.environment.log.warn("Meilisearch unavailable, falling back to Postgres FTS", e)

            val startTime = System.currentTimeMillis()
            val items = searchRepository.search(
                query = query,
                platform = platform,
                sort = sort,
                limit = limit,
                offset = offset,
            )
            val elapsed = (System.currentTimeMillis() - startTime).toInt()

            call.respond(SearchResponse(
                items = items,
                totalHits = items.size,
                processingTimeMs = elapsed,
                source = "postgres",
            ))
        }
    }
}
