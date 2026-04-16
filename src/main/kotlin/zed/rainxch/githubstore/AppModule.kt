package zed.rainxch.githubstore

import org.koin.dsl.module
import zed.rainxch.githubstore.db.EventRepository

val appModule = module {
    single { EventRepository() }
}
