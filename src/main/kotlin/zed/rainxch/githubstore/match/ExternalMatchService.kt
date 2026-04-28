package zed.rainxch.githubstore.match

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.ingest.GitHubRepo
import zed.rainxch.githubstore.ingest.GitHubRelease
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.util.FeatureFlags

open class ExternalMatchService(
    private val signingFingerprintRepository: SigningFingerprintRepository,
    private val cache: ResourceCacheRepository,
    // Shares the rotation pool with GitHubSearchClient so external-match
    // upstream calls don't independently exhaust GitHub's anonymous limit
    // (60/hr per source IP — our VPS IP) and respect the quiet-window
    // guarantee for the daily Python fetcher.
    private val searchClient: GitHubSearchClient,
) {
    private val log = LoggerFactory.getLogger(ExternalMatchService::class.java)

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 8_000
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GitHubSearchResults(val items: List<GitHubRepo> = emptyList())

    /**
     * Match a single candidate. Returns up to 5 ranked candidates per spec.
     *
     * Strategy priority (first hit wins for non-search paths):
     *   1. Manifest hint present  → GET /repos/{owner}/{repo} → confidence 1.0
     *   2. Signing fingerprint    → DB lookup                  → confidence 0.92
     *   3. Search                 → score top hits             → confidence ≤ 0.85
     *
     * Strategies 1 and 2 are exclusive: if either returns a match, search is
     * NOT performed. Strategy 3 only runs when neither produced a hit.
     */
    open suspend fun matchOne(req: ExternalMatchCandidateRequest): List<ExternalMatchCandidate> {
        if (FeatureFlags.disableLiveGitHubPassthrough) {
            // Cached results may still serve via the search path's cache;
            // for live HTTP we drop to fingerprint-only.
            return matchByFingerprint(req)
        }

        // 1. Manifest hint
        val manifestMatch = req.manifestHint?.let { hint ->
            val owner = hint.owner?.takeIf { it.isNotBlank() }
            val repo = hint.repo?.takeIf { it.isNotBlank() }
            if (owner != null && repo != null) validateManifestHint(owner, repo) else null
        }
        if (manifestMatch != null) return listOf(manifestMatch)

        // 2. Fingerprint
        val fingerprintMatches = matchByFingerprint(req)
        if (fingerprintMatches.isNotEmpty()) return fingerprintMatches

        // 3. Search (cache by (packageName, appLabel) per spec — fingerprint
        // is intentionally excluded from the key so a returning user with a
        // different fingerprint gets a fresh look-up).
        val cacheKey = cacheKey(req.packageName, req.appLabel)
        val existing = cache.get(cacheKey)
        if (existing != null && existing.isFresh() && existing.status == 200) {
            runCatching {
                return json.decodeFromString<List<ExternalMatchCandidate>>(existing.body)
            }.onFailure { log.warn("Cached external-match payload failed to decode; refetching") }
        }

        val searchMatches = searchAndScore(req)
        runCatching {
            cache.put(
                key = cacheKey,
                body = json.encodeToString(searchMatches),
                etag = null,
                status = 200,
                contentType = "application/json",
                ttlSeconds = CACHE_TTL_SECONDS,
            )
        }.onFailure { log.warn("Failed to cache external-match result: {}", it.message) }
        return searchMatches
    }

    private suspend fun matchByFingerprint(req: ExternalMatchCandidateRequest): List<ExternalMatchCandidate> {
        val fp = req.signingFingerprint?.takeIf { it.isNotBlank() } ?: return emptyList()
        return signingFingerprintRepository.lookup(fp).map { (owner, repo) ->
            ExternalMatchCandidate(
                owner = owner,
                repo = repo,
                confidence = FINGERPRINT_CONFIDENCE,
                source = "fingerprint",
                stars = null,
                description = null,
            )
        }
    }

    private suspend fun validateManifestHint(owner: String, repo: String): ExternalMatchCandidate? {
        val token = searchClient.pickFallbackToken()
        val resp = try {
            http.get("https://api.github.com/repos/$owner/$repo") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ExternalMatch)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
            }
        } catch (e: Exception) {
            log.warn("Manifest hint validation failed for {}/{}: {}", owner, repo, e.message)
            return null
        }
        if (!resp.status.isSuccess()) return null
        val body: GitHubRepo = runCatching { resp.body<GitHubRepo>() }.getOrNull() ?: return null
        return ExternalMatchCandidate(
            owner = owner,
            repo = repo,
            confidence = MANIFEST_CONFIDENCE,
            source = "manifest",
            stars = body.stargazersCount,
            description = body.description,
        )
    }

    private suspend fun searchAndScore(req: ExternalMatchCandidateRequest): List<ExternalMatchCandidate> {
        val token = searchClient.pickFallbackToken()
        val items: List<GitHubRepo> = try {
            val response = http.get("https://api.github.com/search/repositories") {
                parameter("q", "${req.appLabel} fork:false")
                // Spec §3.2 says "score top 5 search results" — fetch exactly 5
                // so the per-result asset-probe fan-out matches what we score.
                parameter("per_page", 5)
                parameter("sort", "stars")
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ExternalMatch)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
            }
            if (!response.status.isSuccess()) {
                emptyList()
            } else {
                val parsed: GitHubSearchResults = response.body()
                parsed.items
            }
        } catch (e: Exception) {
            log.warn("Search query failed for {}: {}", req.packageName, e.message)
            return emptyList()
        }
        if (items.isEmpty()) return emptyList()

        // For each repo, also need to know whether recent releases ship Android
        // assets. Probe in parallel, capped at the same set size.
        val withAssetSignal = coroutineScope {
            items.map { repo ->
                async { repo to hasRecentAndroidAsset(repo.owner.login, repo.name) }
            }.awaitAll()
        }

        val hits = withAssetSignal.map { (repo, hasAsset) ->
            ExternalMatchScorer.SearchHit(
                owner = repo.owner.login,
                repo = repo.name,
                stars = repo.stargazersCount,
                description = repo.description,
                hasAndroidAssetInRecentReleases = hasAsset,
            )
        }

        return ExternalMatchScorer
            .rank(req.packageName, req.appLabel, hits, limit = 5)
            .map { (hit, confidence) ->
                ExternalMatchCandidate(
                    owner = hit.owner,
                    repo = hit.repo,
                    confidence = confidence,
                    source = "search",
                    stars = hit.stars,
                    description = hit.description,
                )
            }
    }

    private suspend fun hasRecentAndroidAsset(owner: String, repo: String): Boolean {
        val token = searchClient.pickFallbackToken()
        val resp = try {
            http.get("https://api.github.com/repos/$owner/$repo/releases?per_page=5") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ExternalMatch)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
            }
        } catch (_: Exception) {
            return false
        }
        if (!resp.status.isSuccess()) return false
        val releases: List<GitHubRelease> = runCatching {
            resp.body<List<GitHubRelease>>()
        }.getOrNull() ?: return false
        return releases.any { release ->
            release.assets.any { asset ->
                asset.name.endsWith(".apk", ignoreCase = true) ||
                    asset.name.endsWith(".aab", ignoreCase = true)
            }
        }
    }

    private fun cacheKey(packageName: String, appLabel: String): String =
        // NUL byte separator. Both packageName (regex-validated to no
        // control chars) and appLabel can never legally contain a NUL,
        // so the join is unambiguously reversible — collision-proof
        // even if route validation regresses in a future change.
        "external-match:${packageName}\u0001${appLabel}"

    private companion object {
        const val MANIFEST_CONFIDENCE = 1.0
        const val FINGERPRINT_CONFIDENCE = 0.92
        const val CACHE_TTL_SECONDS = 24L * 60 * 60
    }
}
