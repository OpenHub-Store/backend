package zed.rainxch.githubstore

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.sentry.Sentry
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import zed.rainxch.githubstore.db.DatabaseFactory
import zed.rainxch.githubstore.ingest.RepoRefreshWorker
import zed.rainxch.githubstore.ingest.SearchMissWorker
import zed.rainxch.githubstore.ingest.SignalAggregationWorker
import zed.rainxch.githubstore.match.FdroidSeedWorker
import zed.rainxch.githubstore.routes.configureRouting

fun main() {
    validateProductionEnv()

    val sentryDsn = System.getenv("SENTRY_DSN")
    if (!sentryDsn.isNullOrBlank()) {
        Sentry.init { options ->
            options.dsn = sentryDsn
            options.tracesSampleRate = 0.01
            options.environment = System.getenv("APP_ENV") ?: "production"
            options.release = "github-store-backend@${BuildInfo.version}"
            // Scrub credential-bearing headers from every event before they
            // leave the process. Sentry's default scrubber catches a few
            // common ones; this list is what we explicitly forward and what
            // an attacker could set to test the pipeline.
            options.setBeforeSend { event, _ ->
                event.request?.headers?.let { headers ->
                    listOf(
                        "Authorization", "X-GitHub-Token", "X-Admin-Token",
                        "Cookie", "Set-Cookie", "X-Forwarded-For",
                        "CF-Connecting-IP",
                    ).forEach { headers.remove(it) }
                }
                event
            }
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

    val signalAggregationWorker by inject<SignalAggregationWorker>()
    signalAggregationWorker.start()

    val repoRefreshWorker by inject<RepoRefreshWorker>()
    repoRefreshWorker.start()

    val fdroidSeedWorker by inject<FdroidSeedWorker>()
    fdroidSeedWorker.start()
}

// Under APP_ENV=production, refuse to start unless the critical secrets are
// set explicitly. Otherwise dev defaults (password "githubstore", "devkey",
// etc.) could silently bind a production deploy to a local Postgres/Meili
// that happens to be reachable. Required list is intentionally narrow to
// keep dev iteration friction-free.
private fun validateProductionEnv() {
    if (System.getenv("APP_ENV") != "production") return
    val required = listOf(
        "DATABASE_URL",
        "DATABASE_PASSWORD",
        "MEILI_URL",
        "MEILI_MASTER_KEY",
        "GITHUB_OAUTH_CLIENT_ID",
        // Pepper for SHA-256 hashing of device IDs before they hit Postgres.
        // Required in prod so a stolen DB dump can't be brute-forced into a
        // device-ID lookup table without also stealing the env.
        "DEVICE_ID_PEPPER",
    )
    val missing = required.filter { System.getenv(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        System.err.println(
            "FATAL: missing required env vars under APP_ENV=production: $missing"
        )
        throw IllegalStateException(
            "Missing required env vars: $missing. " +
                "Set them in /opt/github-store-backend/.env before deploy."
        )
    }
}
