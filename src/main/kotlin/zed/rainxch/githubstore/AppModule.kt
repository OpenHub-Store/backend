package zed.rainxch.githubstore

import org.koin.dsl.module
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.db.SearchRepository

val appModule = module {
    single { EventRepository() }
    single { RepoRepository() }
    single { SearchRepository() }
    single { MeilisearchClient() }
}
