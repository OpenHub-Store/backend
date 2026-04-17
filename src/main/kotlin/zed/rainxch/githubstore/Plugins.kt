package zed.rainxch.githubstore

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.sentry.Sentry
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

fun Application.configureHTTP() {
    install(DefaultHeaders) {
        header("X-Engine", "github-store-backend")
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
    }

    install(Compression) {
        gzip {
            priority = 1.0
        }
    }

    install(RateLimit) {
        // General API: 120 requests per minute per IP
        global {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
        // Events endpoint: stricter (30 per minute)
        register(RateLimitName("events")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
        // Search: moderate (60 per minute) — on-demand GitHub calls are expensive
        register(RateLimitName("search")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        // Don't log query parameters to avoid leaking search terms
        disableDefaultColors()
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            Sentry.captureException(cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error")
            )
        }
    }
}
