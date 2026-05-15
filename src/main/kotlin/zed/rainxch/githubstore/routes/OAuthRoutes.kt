package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.oauth.OAuthEphemeralStore
import zed.rainxch.githubstore.oauth.OAuthEphemeralStore.Companion.NAMESPACE_HANDOFF
import zed.rainxch.githubstore.oauth.OAuthEphemeralStore.Companion.NAMESPACE_STATE
import zed.rainxch.githubstore.oauth.OAuthExchangeService
import zed.rainxch.githubstore.oauth.OAuthServiceAuth
import zed.rainxch.githubstore.requireMaxBody
import zed.rainxch.githubstore.util.ApiError
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

private val log = LoggerFactory.getLogger("OAuthRoutes")

// 60-second TTL for both state + handoff per the security baseline. Long
// enough that a slow user finishing OAuth in another tab still works, short
// enough that a leaked handoff_id is effectively useless by the time anyone
// could replay it.
private val TTL = Duration.ofSeconds(60)

private const val STATE_BODY_MAX = 1L * 1024
private const val EXCHANGE_BODY_MAX = 4L * 1024

// state + code_challenge + code_verifier + handoff_id all encode N random
// bytes as unpadded base64url. Validation rejects anything else outright so
// the upstream call never sees malformed input.
private val BASE64URL_43 = Regex("^[A-Za-z0-9_-]{43}$")
private val BASE64URL_43_TO_128 = Regex("^[A-Za-z0-9_-]{43,128}$")
private const val CODE_MAX_LEN = 200

private val parseJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val urlBase64 = Base64.getUrlEncoder().withoutPadding()
private val secureRandom = SecureRandom()

@Serializable
private data class StateRequest(val state: String, val code_challenge: String)

@Serializable
private data class ExchangeRequest(val code: String, val state: String, val code_verifier: String)

@Serializable
private data class ExchangeResponse(val handoff_id: String)

@Serializable
private data class HandoffResponse(val access_token: String)

// JSON we persist under `oauth:state:<state>`. Stored as a string, not a
// scalar, because we may add fields (e.g. redirect target) without a
// migration — the column is `TEXT`.
@Serializable
private data class StoredState(val code_challenge: String, val created_at_ms: Long)

fun Route.oauthRoutes(
    store: OAuthEphemeralStore,
    exchangeService: OAuthExchangeService,
    serviceAuth: OAuthServiceAuth,
) {
    route("/oauth") {

        // ---- POST /v1/oauth/state ----
        // Website calls this server-to-server before redirecting the user to
        // GitHub. The app generated the verifier; the website forwards only
        // the challenge (= SHA256(verifier)). 60s TTL.
        rateLimit(RateLimitName("oauth-state")) {
        post("/state") {
            if (!serviceAuth.authorize(call)) return@post
            if (!call.requireMaxBody(STATE_BODY_MAX)) return@post

            val req = try {
                call.receive<StateRequest>()
            } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body"))
            }

            if (!BASE64URL_43.matches(req.state)) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_state"))
            }
            if (!BASE64URL_43.matches(req.code_challenge)) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_code_challenge"))
            }

            val stored = StoredState(
                code_challenge = req.code_challenge,
                created_at_ms = System.currentTimeMillis(),
            )
            val ok = store.setEx(
                namespace = NAMESPACE_STATE,
                key = req.state,
                value = parseJson.encodeToString(StoredState.serializer(), stored),
                ttl = TTL,
            )
            if (!ok) {
                // Duplicate state on the wire is either a buggy retry or a
                // replay — refuse. The legitimate flow always uses a fresh
                // random state per attempt.
                log.info(
                    "[oauth-state] state={} duplicate",
                    req.state.take(8),
                )
                return@post call.respond(HttpStatusCode.Conflict, ApiError("state_already_registered"))
            }

            log.info("[oauth-state] state={} ok", req.state.take(8))
            call.respond(HttpStatusCode.NoContent)
        }
        }

        // ---- POST /v1/oauth/exchange ----
        // Website calls this from its /auth/callback handler. The request
        // contains GitHub's `code`, the `state` the app generated, and the
        // PKCE `code_verifier` the app posted to the website earlier in the
        // flow. The backend verifies SHA256(verifier) == stored challenge,
        // hits GitHub for the token, and mints a one-shot handoff_id.
        rateLimit(RateLimitName("oauth-exchange")) {
        post("/exchange") {
            if (!serviceAuth.authorize(call)) return@post
            if (!call.requireMaxBody(EXCHANGE_BODY_MAX)) return@post

            val req = try {
                call.receive<ExchangeRequest>()
            } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body"))
            }

            if (req.code.isBlank() || req.code.length > CODE_MAX_LEN) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_code"))
            }
            if (!BASE64URL_43.matches(req.state)) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_state"))
            }
            if (!BASE64URL_43_TO_128.matches(req.code_verifier)) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_code_verifier"))
            }
            val statePrefix = req.state.take(8)

            val storedRaw = store.get(NAMESPACE_STATE, req.state) ?: run {
                log.info("[oauth-exchange] state={} error=state_missing_or_expired", statePrefix)
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("state_missing_or_expired"))
            }
            val stored = try {
                parseJson.decodeFromString(StoredState.serializer(), storedRaw)
            } catch (_: Exception) {
                log.error("[oauth-exchange] state={} error=stored_state_unreadable", statePrefix)
                store.del(NAMESPACE_STATE, req.state)
                return@post call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
            }

            val verifierChallenge = pkceChallenge(req.code_verifier)
            if (!constantTimeEquals(verifierChallenge, stored.code_challenge)) {
                log.info("[oauth-exchange] state={} error=pkce_mismatch", statePrefix)
                store.del(NAMESPACE_STATE, req.state)
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("pkce_mismatch"))
            }

            when (val result = exchangeService.exchange(req.code)) {
                is OAuthExchangeService.Result.Success -> {
                    val handoffId = randomBase64Url(32)
                    val ok = store.setEx(
                        namespace = NAMESPACE_HANDOFF,
                        key = handoffId,
                        value = result.accessToken,
                        ttl = TTL,
                    )
                    if (!ok) {
                        // Re-roll once; with 32 random bytes a collision is
                        // astronomically unlikely but a defensive retry costs
                        // nothing.
                        val retryId = randomBase64Url(32)
                        val retryOk = store.setEx(NAMESPACE_HANDOFF, retryId, result.accessToken, TTL)
                        if (!retryOk) {
                            log.error("[oauth-exchange] state={} error=handoff_collision_twice", statePrefix)
                            return@post call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
                        }
                        store.del(NAMESPACE_STATE, req.state)
                        log.info("[oauth-exchange] state={} ok", statePrefix)
                        return@post call.respond(ExchangeResponse(retryId))
                    }
                    store.del(NAMESPACE_STATE, req.state)
                    log.info("[oauth-exchange] state={} ok", statePrefix)
                    call.respond(ExchangeResponse(handoffId))
                }

                is OAuthExchangeService.Result.UpstreamError -> {
                    log.info("[oauth-exchange] state={} error=github_{}", statePrefix, result.errorCode)
                    store.del(NAMESPACE_STATE, req.state)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("github_${result.errorCode}", "GitHub rejected the authorization code"),
                    )
                }

                OAuthExchangeService.Result.UpstreamFailure -> {
                    log.warn("[oauth-exchange] state={} error=github_unreachable", statePrefix)
                    // Don't burn the state on a transient upstream failure —
                    // the website may retry within the 60s window.
                    call.respond(HttpStatusCode.BadGateway, ApiError("github_unreachable"))
                }
            }
        }
        }

        // ---- POST /v1/oauth/handoff/{id} ----
        // App calls this with the handoff_id it received via deep link from
        // the website. Single-use: GETDEL semantics mean a second call always
        // returns 404. Public — no S2S auth.
        rateLimit(RateLimitName("oauth-handoff")) {
        post("/handoff/{id}") {
            val rawId = call.parameters["id"].orEmpty()
            if (!BASE64URL_43.matches(rawId)) {
                return@post call.respond(HttpStatusCode.NotFound, ApiError("handoff_not_found"))
            }

            val token = store.getDel(NAMESPACE_HANDOFF, rawId)
            if (token == null) {
                log.info("[oauth-handoff] id={} miss", rawId.take(8))
                return@post call.respond(HttpStatusCode.NotFound, ApiError("handoff_not_found"))
            }

            log.info("[oauth-handoff] id={} ok", rawId.take(8))
            // Token returned to the app over the same HTTPS channel that
            // every other endpoint uses. The app stores it locally and we
            // never see it again on the backend.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HandoffResponse(token))
        }
        }
    }
}

private fun pkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
    return urlBase64.encodeToString(digest)
}

private fun randomBase64Url(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    secureRandom.nextBytes(bytes)
    return urlBase64.encodeToString(bytes)
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
