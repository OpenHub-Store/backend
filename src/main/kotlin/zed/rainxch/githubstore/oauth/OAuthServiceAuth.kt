package zed.rainxch.githubstore.oauth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import zed.rainxch.githubstore.util.ApiError

// Gate the two S2S OAuth endpoints (/v1/oauth/state and /v1/oauth/exchange).
//
// Two checks, both must pass:
//   1. Shared secret  — `X-Oauth-Service-Token` header must equal the env
//      OAUTH_SERVICE_TOKEN (constant-time compared so a leaked length isn't
//      a side channel).
//   2. Host allowlist — `Host:` header must be in OAUTH_SERVICE_ALLOWED_HOSTS
//      (comma-separated env). Belt-and-suspenders on top of (1): even if the
//      secret leaks, the request still has to land on the canonical vhost,
//      not the api-direct fallback or some accidental Cloudflare Workers
//      route. If the env is unset or blank the host check is skipped — that
//      mode is for local dev (`APP_ENV != production`) only; production
//      MUST configure both env vars or both routes refuse every request.
class OAuthServiceAuth(
    private val expectedToken: String?,
    allowedHostsCsv: String?,
) {
    private val allowedHosts: Set<String> =
        allowedHostsCsv?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    suspend fun authorize(call: ApplicationCall): Boolean {
        if (expectedToken.isNullOrBlank()) {
            // Production refuses every request when the secret isn't set —
            // operator missed an env var, so don't quietly accept anything.
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("oauth_not_configured"))
            return false
        }

        val provided = call.request.headers["X-Oauth-Service-Token"].orEmpty()
        if (!constantTimeEquals(provided, expectedToken)) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("service_auth_required"))
            return false
        }

        // Production sets allowedHosts; dev (APP_ENV != production) can leave
        // the env blank and the host check no-ops, so localhost works.
        val isProd = System.getenv("APP_ENV") == "production"
        if (allowedHosts.isNotEmpty()) {
            val host = call.request.headers[HttpHeaders.Host]?.lowercase().orEmpty()
            // Strip optional :port suffix — operators sometimes set "api.example.org"
            // in the env but the Host header arrives as "api.example.org:443".
            val hostOnly = host.substringBefore(':')
            if (hostOnly !in allowedHosts) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("host_not_allowed"))
                return false
            }
        } else if (isProd) {
            // Empty allowlist in production is a misconfiguration — refuse.
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("oauth_not_configured"))
            return false
        }

        return true
    }

    // Compare two strings in constant time relative to the length they share.
    // Prevents the trivial timing side channel that `==` introduces by
    // short-circuiting on the first mismatched byte.
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
