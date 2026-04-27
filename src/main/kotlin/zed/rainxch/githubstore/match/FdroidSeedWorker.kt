package zed.rainxch.githubstore.match

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.SigningFingerprints
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// Daily ingester that pulls the F-Droid index and seeds (certificate, owner, repo)
// rows into the signing_fingerprint table. F-Droid's index ships fingerprint +
// source-code URL for every package; we filter to GitHub-hosted URLs and skip
// the rest. The /v1/external-match fingerprint strategy and the /v1/signing-seeds
// dump both read this table.
//
// Cadence: every 24h. Initial run on startup if the table is empty.
class FdroidSeedWorker {

    private val log = LoggerFactory.getLogger(FdroidSeedWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cycleInterval = 24.hours
    private val initialDelay = 2.minutes

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun start(): Job = scope.launch {
        // Run-on-startup-if-empty path. Avoids waiting up to 24h on a fresh
        // deploy for the first sync to land.
        delay(initialDelay)
        if (tableIsEmpty()) {
            log.info("FdroidSeedWorker: signing_fingerprint table empty, running initial seed")
            runOnce()
        }
        while (true) {
            delay(cycleInterval)
            runOnce()
        }
    }

    private fun tableIsEmpty(): Boolean = try {
        transaction {
            SigningFingerprints.selectAll().limit(1).empty()
        }
    } catch (e: Exception) {
        log.warn("FdroidSeedWorker: failed to check table emptiness: {}", e.message)
        true
    }

    suspend fun runOnce() {
        try {
            val rows = fetchAndExtract()
            if (rows.isEmpty()) {
                log.warn("FdroidSeedWorker: extracted zero rows; skipping upsert")
                return
            }
            val repository = SigningFingerprintRepository()
            repository.upsertBatch(rows)
            log.info("FdroidSeedWorker: upserted {} signing-fingerprint rows", rows.size)
        } catch (e: Exception) {
            log.error("FdroidSeedWorker cycle failed", e)
            Sentry.captureException(e)
        }
    }

    /**
     * Fetches F-Droid's index-v2.json and extracts (fingerprint, owner, repo,
     * observedAt) tuples for every package whose source-code URL is a github.com
     * repo. Skips entries with malformed certs or non-GitHub source URLs.
     *
     * F-Droid's v2 index shape (relevant subset):
     *   {
     *     "packages": {
     *       "<packageId>": {
     *         "metadata": { "sourceCode": "https://github.com/<owner>/<repo>" },
     *         "preferredSigner": "<sha256-uppercase-hex>",
     *         "versions": {  // not used here
     *           "<hash>": { "manifest": { "signer": { "sha256": ["<hash>"] } } }
     *         }
     *       }
     *     }
     *   }
     */
    private suspend fun fetchAndExtract(): List<SigningSeedRow> {
        val resp = http.get(FDROID_INDEX_URL) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (FdroidSeedWorker)")
        }
        if (!resp.status.isSuccess()) {
            log.warn("FdroidSeedWorker: F-Droid index responded {}", resp.status.value)
            return emptyList()
        }

        val root: JsonElement = resp.body()
        val packages = root.jsonObject["packages"]?.jsonObject ?: run {
            log.warn("FdroidSeedWorker: F-Droid index missing 'packages' object")
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val out = mutableListOf<SigningSeedRow>()

        for ((_, pkgEl) in packages) {
            val pkg = pkgEl.jsonObject
            val metadata = pkg["metadata"]?.jsonObject ?: continue
            val sourceCode = metadata["sourceCode"]?.toLocalized() ?: continue
            val (owner, repo) = parseGithubUrl(sourceCode) ?: continue

            // Prefer the per-version signer hashes (these are the actual cert
            // SHA-256s that ship in releases). Fall back to preferredSigner.
            val fingerprints = extractFingerprints(pkg)
            for (fp in fingerprints) {
                out += SigningSeedRow(
                    fingerprint = formatColonHex(fp),
                    owner = owner,
                    repo = repo,
                    observedAt = now,
                )
            }
        }

        return out.distinctBy { Triple(it.fingerprint, it.owner, it.repo) }
    }

    private fun extractFingerprints(pkg: JsonObject): Set<String> {
        val out = mutableSetOf<String>()
        // 1. preferredSigner is a top-level uppercase hex string.
        pkg["preferredSigner"]?.jsonPrimitive?.contentOrNull?.let { out += it }
        // 2. versions[*].manifest.signer.sha256[*]
        pkg["versions"]?.jsonObject?.values?.forEach { ver ->
            val sha = ver.jsonObject["manifest"]?.jsonObject
                ?.get("signer")?.jsonObject
                ?.get("sha256")?.jsonArray
            sha?.forEach { entry ->
                entry.jsonPrimitive.contentOrNull?.let { out += it }
            }
        }
        // F-Droid sometimes ships these as lowercase or with mixed length —
        // filter to canonical 64-char hex only.
        return out.filter { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            .toSet()
    }

    /**
     * F-Droid's metadata strings are localized:
     *   "sourceCode": { "en-US": "https://github.com/...", "de": "..." }
     * but sometimes also a bare string for legacy entries. Pick any non-empty
     * value — they're URLs, not display text, so locale doesn't matter.
     */
    private fun JsonElement.toLocalized(): String? {
        return when (this) {
            is JsonObject -> values.firstNotNullOfOrNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            else -> jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseGithubUrl(url: String): Pair<String, String>? {
        // Accept https://github.com/{owner}/{repo}{/...,.git}? — strip trailing
        // path / query / .git. Reject anything that isn't github.com.
        val match = GITHUB_URL_RE.matchEntire(url.trim()) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return owner to repo
    }

    /** AB:CD:EF:... 32 octets. F-Droid hashes are 64 hex chars; insert colons. */
    private fun formatColonHex(hex: String): String =
        hex.uppercase().chunked(2).joinToString(":")

    private companion object {
        const val FDROID_INDEX_URL = "https://f-droid.org/repo/index-v2.json"

        // Matches https://github.com/owner/repo with optional trailing path or
        // .git suffix. Owner/repo follow GitHub's actual character rules.
        private val GITHUB_URL_RE = Regex(
            "^https?://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})(?:\\.git)?(?:/.*)?$",
        )
    }
}

