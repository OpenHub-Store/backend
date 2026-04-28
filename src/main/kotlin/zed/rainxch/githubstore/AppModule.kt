package zed.rainxch.githubstore

import org.koin.dsl.module
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.db.SearchMissRepository
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.ingest.GitHubDeviceClient
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.ingest.RepoRefreshWorker
import zed.rainxch.githubstore.ingest.SignalAggregationWorker
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import zed.rainxch.githubstore.badge.BadgeService
import zed.rainxch.githubstore.badge.FdroidVersionClient
import zed.rainxch.githubstore.telemetry.TelemetryRepository

val appModule = module {
    single { EventRepository() }
    single { RepoRepository() }
    single { SearchRepository() }
    single { SearchMissRepository() }
    single { ResourceCacheRepository() }
    single { MeilisearchClient() }
    single { GitHubSearchClient(get()) }
    single { GitHubDeviceClient() }
    single { GitHubResourceClient(get()) }
    single { WorkerSupervisor() }
    single { SignalAggregationWorker(get(), get()) }
    single { RepoRefreshWorker(get(), get()) }
    single { SearchMetricsRegistry() }
    single { FdroidVersionClient(packageId = "zed.rainxch.githubstore") }
    single { BadgeService(repoRepository = get(), resourceClient = get(), fdroidClient = get()) }
    single { TelemetryRepository() }
}
