package zed.rainxch.githubstore

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.sentry.Sentry
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import zed.rainxch.githubstore.db.DatabaseFactory
import zed.rainxch.githubstore.ingest.SearchMissWorker
import zed.rainxch.githubstore.routes.configureRouting

fun main() {
    val sentryDsn = System.getenv("SENTRY_DSN")
    if (!sentryDsn.isNullOrBlank()) {
        Sentry.init { options ->
            options.dsn = sentryDsn
            options.tracesSampleRate = 0.1
            options.environment = System.getenv("APP_ENV") ?: "production"
            options.release = "github-store-backend@0.1.0"
        }
    }

    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }

    configureSerialization()
    configureHTTP()
    DatabaseFactory.init()
    configureRouting()

    // Start background workers after routing is configured
    val searchMissWorker by inject<SearchMissWorker>()
    searchMissWorker.start()
}
