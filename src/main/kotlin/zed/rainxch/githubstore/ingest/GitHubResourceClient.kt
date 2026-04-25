package zed.rainxch.githubstore.ingest

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.util.FeatureFlags
import java.util.concurrent.ConcurrentHashMap

class GitHubResourceClient(
    private val cacheRepository: ResourceCacheRepository,
) {
    private val log = LoggerFactory.getLogger(GitHubResourceClient::class.java)

    private val fallbackTokenProvider: (() -> String?)? = null

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    // Collapses concurrent fetches for the same key into one upstream call.
    // Each entry is removed in fetchCached's finally block once the lock is
    // released, so the map stays bounded by in-flight fetches.
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun fetchCached(
        cacheKey: String,
        upstreamUrl: String,
        userToken: String?,
        ttlSeconds: Long,
        negativeTtlSeconds: Long = 900,   // 15 min for 404/451/5xx
        contentType: String = "application/json",
        acceptHeader: String = "application/vnd.github+json",
    ): Result {
        // Fresh cache hit — no upstream call, no lock.
        cacheRepository.get(cacheKey)?.let { existing ->
            if (existing.isFresh()) {
                return if (existing.status in 200..299) {
                    Result.Hit(existing.body, existing.status, existing.contentType)
                } else {
                    Result.NegativeHit(existing.status)
                }
            }
        }

        // Kill switch: serve cached only, never hit upstream.
        if (FeatureFlags.disableLiveGitHubPassthrough) {
            return Result.UpstreamError("passthrough_disabled")
        }

        // Stale or missing — serialize per-key to avoid thundering herd.
        val mutex = fetchLocks.computeIfAbsent(cacheKey) { Mutex() }
        try {
            return mutex.withLock {
                // Re-check after acquiring: another waiter may have just refreshed it.
                val afterLock = cacheRepository.get(cacheKey)
                if (afterLock != null && afterLock.isFresh()) {
                    return@withLock if (afterLock.status in 200..299) {
                        Result.Hit(afterLock.body, afterLock.status, afterLock.contentType)
                    } else {
                        Result.NegativeHit(afterLock.status)
                    }
                }

                fetchFromUpstream(
                    cacheKey = cacheKey,
                    upstreamUrl = upstreamUrl,
                    userToken = userToken,
                    ttlSeconds = ttlSeconds,
                    negativeTtlSeconds = negativeTtlSeconds,
                    contentType = contentType,
                    acceptHeader = acceptHeader,
                    existingEtag = afterLock?.etag,
                    existingBody = afterLock?.body,
                    existingStatus = afterLock?.status,
                    existingContentType = afterLock?.contentType,
                )
            }
        } finally {
            // Reclaim the per-key mutex so the map doesn't grow unbounded.
            // remove(key, value) is a no-op if a concurrent waiter has already
            // installed a new mutex for this key — safe under contention.
            fetchLocks.remove(cacheKey, mutex)
        }
    }

    private suspend fun fetchFromUpstream(
        cacheKey: String,
        upstreamUrl: String,
        userToken: String?,
        ttlSeconds: Long,
        negativeTtlSeconds: Long,
        contentType: String,
        acceptHeader: String,
        existingEtag: String?,
        existingBody: String?,
        existingStatus: Int?,
        existingContentType: String?,
    ): Result {
        val token = userToken ?: fallbackTokenProvider?.invoke()

        val response = try {
            http.get(upstreamUrl) {
                header(HttpHeaders.Accept, acceptHeader)
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ResourceProxy)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
                if (existingEtag != null) header(HttpHeaders.IfNoneMatch, existingEtag)
            }
        } catch (e: Exception) {
            // Network/timeout. If we have a stale-but-usable cached copy, serve
            // it — better to return stale data than to 502 when the client
            // just wants to render a Details screen.
            log.warn("Upstream fetch failed: url={} err={}", upstreamUrl, e.message)
            if (existingBody != null && existingStatus in 200..299) {
                return Result.StaleFallback(existingBody, existingStatus!!, existingContentType ?: contentType)
            }
            return Result.UpstreamError(e.message ?: "unknown")
        }

        val status = response.status.value
        val newEtag = response.headers[HttpHeaders.ETag]

        // 304 Not Modified — body unchanged, just bump TTL and serve cached.
        if (status == 304 && existingBody != null) {
            cacheRepository.refreshTtl(cacheKey, ttlSeconds)
            return Result.Hit(existingBody, existingStatus ?: 200, existingContentType ?: contentType)
        }

        // 200-299: fresh body, cache it.
        if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            cacheRepository.put(
                key = cacheKey,
                body = body,
                etag = newEtag,
                status = status,
                contentType = contentType,
                ttlSeconds = ttlSeconds,
            )
            return Result.Hit(body, status, contentType)
        }

        // 404 / 410 / 451 / etc. Cache the negative response briefly so a
        // flood of requests for nonexistent repos can't burn our quota.
        if (status in 400..499) {
            cacheRepository.put(
                key = cacheKey,
                body = "",
                etag = null,
                status = status,
                contentType = contentType,
                ttlSeconds = negativeTtlSeconds,
            )
            return Result.NegativeHit(status)
        }

        // 5xx from GitHub — transient, don't cache. Serve stale if we have it.
        log.warn("Upstream 5xx: url={} status={}", upstreamUrl, status)
        if (existingBody != null && existingStatus in 200..299) {
            return Result.StaleFallback(existingBody, existingStatus!!, existingContentType ?: contentType)
        }
        return Result.UpstreamError("upstream $status")
    }

    sealed class Result {
        data class Hit(val body: String, val status: Int, val contentType: String) : Result()
        data class StaleFallback(val body: String, val status: Int, val contentType: String) : Result()
        data class NegativeHit(val status: Int) : Result()
        data class UpstreamError(val message: String) : Result()
    }
}
