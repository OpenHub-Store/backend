package zed.rainxch.githubstore

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import io.sentry.Sentry
import java.util.UUID
import zed.rainxch.githubstore.routes.ADMIN_BASIC_AUTH
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.hours
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

private val REQUEST_ID_KEY = AttributeKey<String>("RequestId")

fun Application.configureHTTP() {
    install(DefaultHeaders) {
        header("X-Engine", "github-store-backend")
    }

    // Mint a request ID early in the pipeline so every log line and error
    // report can correlate. Respects a client-supplied X-Request-ID if
    // present (useful when debugging a chain of services), otherwise
    // generates a short random hex.
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val incoming = call.request.headers["X-Request-ID"]?.take(64)
        val id = incoming ?: UUID.randomUUID().toString().substring(0, 12)
        call.attributes.put(REQUEST_ID_KEY, id)
        call.response.header("X-Request-ID", id)
    }

    // CORS is only useful for browser-based callers. The KMP client never sends
    // Origin (native HttpClient), so this only affects the admin dashboard (same
    // origin as the API — doesn't need CORS) and any future web surface. Pinning
    // to our own domains removes a CSRF foothold on /v1/events from malicious
    // third-party pages without breaking anything we actually serve.
    install(CORS) {
        allowHost("github-store.org", subDomains = listOf("api", "api-direct", "www"))
        // localhost dev origins are only useful when developing the admin
        // dashboard or a future web client locally. Gating them on APP_ENV
        // keeps them out of the production allowlist so a malicious page
        // can't pretend to be localhost via header forgery against prod.
        if (System.getenv("APP_ENV") != "production") {
            allowHost("localhost:8080")
            allowHost("localhost:5173") // vite dev default, harmless if unused
        }
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-GitHub-Token")
        // X-Admin-Token is intentionally NOT in the CORS allowlist. Admin
        // endpoints are reached via curl/SSH only — never from a browser —
        // so allowing the header would only enable a CSRF-adjacent abuse
        // path (e.g. tricking an admin's browser into firing requests).
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
    }

    install(Compression) {
        gzip {
            priority = 1.0
        }
    }

    // Route HEAD requests to the matching GET handler. Without this, HEAD
    // returns 404 even when GET works — confusing for `curl -I`, monitoring,
    // and CDN origin probes.
    install(AutoHeadResponse)

    install(RateLimit) {
        // General API: 120 requests per minute per IP.
        //
        // Note on the key: we only consult X-Forwarded-For (set by Caddy to
        // the real TCP source) with a socket-IP fallback. The CF-Connecting-IP
        // branch was removed when we moved off Cloudflare onto Gcore — Gcore
        // doesn't set that header, so leaving it in only created a forgery
        // path: any client could send `CF-Connecting-IP: <random>` and rotate
        // past the limiter at will. Same reasoning applies to every bucket
        // below.
        global {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
        // Events endpoint: stricter (30 per minute)
        register(RateLimitName("events")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
        // Search: moderate (60 per minute) — on-demand GitHub calls are expensive
        register(RateLimitName("search")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
        // Badges: 60/min/IP. Embedded in READMEs so a single popular repo can
        // generate steady traffic; the limit is per-viewer-IP, not per-repo.
        register(RateLimitName("badges")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim() ?: "unknown"
            }
        }
        // Auth device-flow start: low volume (one per login attempt). 10/hr/IP
        // keeps abuse impossible without blocking legitimate retries after a
        // failed or cancelled flow.
        register(RateLimitName("auth-start")) {
            rateLimiter(limit = 10, refillPeriod = 1.hours)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
        // Auth device-flow poll: a real flow is ~180 polls over 15min at 5s
        // intervals, so 200/hr/IP fits one full flow plus a small margin.
        register(RateLimitName("auth-poll")) {
            rateLimiter(limit = 200, refillPeriod = 1.hours)
            requestKey { call ->
                call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: "unknown"
            }
        }
    }

    // Basic Auth for the /v1/internal/dashboard HTML page. Username is
    // ignored (anything goes); password must match ADMIN_TOKEN. Matches the
    // token-gated JSON /v1/internal/metrics path so the browser gets a single
    // credential to carry across both fetches.
    val adminToken = System.getenv("ADMIN_TOKEN")?.takeIf { it.isNotBlank() }
    install(Authentication) {
        basic(ADMIN_BASIC_AUTH) {
            realm = "github-store-admin"
            validate { creds ->
                if (adminToken != null && creds.password == adminToken) {
                    UserIdPrincipal(creds.name)
                } else null
            }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        disableDefaultColors()
        // Populate SLF4J MDC with the request ID so any structured log
        // consumer (Sentry breadcrumbs, JSON log aggregators) picks it up
        // automatically via the MDC key "rid". Ktor's CallLogging plugin
        // manages MDC lifecycle around the call.
        mdc("rid") { call -> call.attributes.getOrNull(REQUEST_ID_KEY) }
        // Explicit bracket-prefixed access-log line so `grep 'rid=7404b7e8'`
        // on raw log files works without a structured-log pipeline. The
        // default Ktor format is sparse and doesn't carry the request ID
        // into the message body.
        format { call ->
            val rid = call.attributes.getOrNull(REQUEST_ID_KEY) ?: "-"
            val status = call.response.status()?.value ?: "-"
            val method = call.request.httpMethod.value
            val path = call.request.path()
            "[rid=$rid] $status $method $path"
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val rid = call.attributes.getOrNull(REQUEST_ID_KEY)
            call.application.environment.log.error(
                "Unhandled exception (rid=${rid ?: "-"})",
                cause,
            )
            // Tag Sentry events with the same request_id users paste into bug
            // reports — makes the Sentry UI filter one-click.
            Sentry.withScope { scope ->
                if (rid != null) scope.setTag("request_id", rid)
                Sentry.captureException(cause)
            }
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error")
            )
        }
    }
}
