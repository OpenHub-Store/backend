package zed.rainxch.githubstore

import org.koin.dsl.module
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.db.RepoRepository

val appModule = module {
    single { EventRepository() }
    single { RepoRepository() }
}
