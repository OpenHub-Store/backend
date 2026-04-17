package zed.rainxch.githubstore

import org.koin.dsl.module
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.db.SearchMissRepository
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.ingest.SearchMissWorker

val appModule = module {
    single { EventRepository() }
    single { RepoRepository() }
    single { SearchRepository() }
    single { SearchMissRepository() }
    single { MeilisearchClient() }
    single { GitHubSearchClient(get()) }
    single { SearchMissWorker(get(), get()) }
}
