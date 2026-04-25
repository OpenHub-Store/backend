package zed.rainxch.githubstore.routes

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.db.SearchMissRepository
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.ingest.GitHubDeviceClient
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import zed.rainxch.githubstore.badge.BadgeService

fun Application.configureRouting() {
    val eventRepository by inject<EventRepository>()
    val repoRepository by inject<RepoRepository>()
    val searchRepository by inject<SearchRepository>()
    val searchMissRepository by inject<SearchMissRepository>()
    val meilisearchClient by inject<MeilisearchClient>()
    val githubSearchClient by inject<GitHubSearchClient>()
    val deviceClient by inject<GitHubDeviceClient>()
    val resourceClient by inject<GitHubResourceClient>()
    val searchMetrics by inject<SearchMetricsRegistry>()
    val badgeService by inject<BadgeService>()

    routing {
        route("/v1") {
            healthRoutes(meilisearchClient)
            rateLimit(RateLimitName("events")) {
                eventRoutes(eventRepository)
            }
            categoryRoutes(repoRepository)
            topicRoutes(repoRepository)
            repoRoutes(repoRepository, resourceClient)
            rateLimit(RateLimitName("search")) {
                searchRoutes(meilisearchClient, searchRepository, githubSearchClient, searchMissRepository, searchMetrics)
                releasesRoutes(resourceClient)
                readmeRoutes(resourceClient)
                userRoutes(resourceClient)
            }
            authRoutes(deviceClient)
            internalRoutes(searchMetrics)
            badgeRoutes(badgeService)
        }
    }
}
