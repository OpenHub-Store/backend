package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import zed.rainxch.githubstore.match.ExternalMatchEntry
import zed.rainxch.githubstore.match.ExternalMatchRequest
import zed.rainxch.githubstore.match.ExternalMatchResponse
import zed.rainxch.githubstore.match.ExternalMatchService

private const val MAX_CANDIDATES_PER_REQUEST = 25
private val PACKAGE_NAME_RE = Regex("^[\\w.-]{1,255}$")
private val OWNER_RE = Regex("^[\\w.-]{1,39}$")
private val REPO_RE = Regex("^[\\w.-]{1,100}$")
private val FINGERPRINT_RE = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")
private const val MAX_APP_LABEL_LEN = 200
private val INSTALLER_KINDS = setOf(
    "obtainium", "fdroid", "play", "aurora", "galaxy",
    "oem_other", "browser", "sideload", "system",
    "github_store_self", "unknown",
)

fun Route.externalMatchRoutes(service: ExternalMatchService) {
    post("/external-match") {
        val req = call.receive<ExternalMatchRequest>()

        if (req.platform != "android") {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "platform must be 'android' for v1"))
        }
        if (req.candidates.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "candidates must be non-empty"))
        }
        if (req.candidates.size > MAX_CANDIDATES_PER_REQUEST) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "max $MAX_CANDIDATES_PER_REQUEST candidates per request"),
            )
        }

        // Per-candidate validation. Reject the whole batch on any malformed
        // entry rather than silently dropping it — a buggy client that's
        // sending garbage should learn loudly.
        for (c in req.candidates) {
            if (!PACKAGE_NAME_RE.matches(c.packageName)) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_package_name"))
            }
            if (c.appLabel.isBlank() || c.appLabel.length > MAX_APP_LABEL_LEN) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_app_label"))
            }
            c.signingFingerprint?.let { fp ->
                if (!FINGERPRINT_RE.matches(fp)) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_fingerprint"))
                }
            }
            c.installerKind?.let { kind ->
                if (kind !in INSTALLER_KINDS) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_installer_kind"))
                }
            }
            c.manifestHint?.owner?.let { owner ->
                if (!OWNER_RE.matches(owner)) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_manifest_owner"))
                }
            }
            c.manifestHint?.repo?.let { repo ->
                if (!REPO_RE.matches(repo)) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_manifest_repo"))
                }
            }
        }

        // Fan out across candidates in parallel — they're independent and the
        // service's per-candidate work is dominated by HTTP latency to GitHub.
        // Capped at MAX_CANDIDATES_PER_REQUEST = 25, so concurrency stays bounded.
        val matches = coroutineScope {
            req.candidates.map { c ->
                async { ExternalMatchEntry(packageName = c.packageName, candidates = service.matchOne(c)) }
            }.awaitAll()
        }

        call.respond(ExternalMatchResponse(matches = matches))
    }
}
