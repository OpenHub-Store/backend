package zed.rainxch.githubstore.badge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.util.FeatureFlags
import kotlin.time.Duration.Companion.hours

class BadgeService(
    private val repoRepository: RepoRepository,
    private val resourceClient: GitHubResourceClient,
    private val fdroidClient: FdroidVersionClient,
    private val staticUserCount: Long? = System.getenv("BADGE_USER_COUNT")?.toLongOrNull(),
) {
    private val log = LoggerFactory.getLogger(BadgeService::class.java)

    private val fdroidCache = TtlCache<String>(6.hours)
    private val json = Json { ignoreUnknownKeys = true }

    data class Rendered(val svg: String, val degraded: Boolean)

    @Serializable
    private data class GhRelease(val tag_name: String? = null)

    @Serializable
    private data class GhRepo(val stargazers_count: Long? = null)

    suspend fun renderRepoBadge(
        owner: String,
        name: String,
        kind: String,
        styleIndex: Int,
        variant: BadgeVariant,
        labelOverride: String?,
        height: Int,
    ): Rendered? {
        val (text, iconPath, degraded) = resolveRepoMetric(owner, name, kind, labelOverride) ?: return null
        val svg = BadgeRenderer.render(text, iconPath, styleIndex, variant, height)
        return Rendered(svg, degraded)
    }

    suspend fun renderGlobalBadge(
        kind: String,
        styleIndex: Int,
        variant: BadgeVariant,
        labelOverride: String?,
        height: Int,
    ): Rendered? {
        val (text, iconPath, degraded) = resolveGlobal(kind, labelOverride) ?: return null
        val svg = BadgeRenderer.render(text, iconPath, styleIndex, variant, height)
        return Rendered(svg, degraded)
    }

    fun renderStatic(
        label: String,
        iconName: String?,
        styleIndex: Int,
        variant: BadgeVariant,
        height: Int,
    ): String {
        val iconPath = BadgeIcons.byName(iconName)
        return BadgeRenderer.render(label, iconPath, styleIndex, variant, height)
    }

    private suspend fun resolveRepoMetric(
        owner: String,
        name: String,
        kind: String,
        labelOverride: String?,
    ): Triple<String, String?, Boolean>? {
        return when (kind) {
            "downloads" -> {
                val (value, degraded) = repoDownloadCount(owner, name)
                Triple(combine(labelOverride, humanizeCount(value)), BadgeIcons.DOWNLOAD, degraded)
            }
            "release" -> {
                val (value, degraded) = repoLatestReleaseTag(owner, name)
                Triple(combine(labelOverride, value), BadgeIcons.PACKAGE, degraded)
            }
            "stars" -> {
                val (value, degraded) = repoStars(owner, name)
                Triple(combine(labelOverride, "${humanizeCount(value)} stars"), BadgeIcons.STAR, degraded)
            }
            else -> null
        }
    }

    private suspend fun resolveGlobal(kind: String, labelOverride: String?): Triple<String, String?, Boolean>? {
        return when (kind) {
            "users" -> {
                val n = staticUserCount
                val (text, degraded) = if (n == null) "—" to true else "${humanizeCount(n)}+ Users" to false
                Triple(combine(labelOverride, text), BadgeIcons.GROUPS, degraded)
            }
            "fdroid" -> {
                val (value, degraded) = safeFdroid()
                Triple(combine(labelOverride, "F-Droid $value"), BadgeIcons.FDROID, degraded)
            }
            else -> null
        }
    }

    private fun combine(labelOverride: String?, value: String): String {
        if (labelOverride.isNullOrBlank()) return value
        return "$labelOverride $value"
    }

    private fun repoStars(owner: String, name: String): Pair<Long, Boolean> {
        val fromDb = try {
            repoRepository.findByOwnerAndName(owner, name)?.stargazersCount?.toLong()
        } catch (e: Exception) {
            log.warn("DB stars lookup failed: {}", e.message); null
        }
        if (fromDb != null && fromDb > 0) return fromDb to false
        return 0L to true
    }

    private fun repoDownloadCount(owner: String, name: String): Pair<Long, Boolean> {
        val fromDb = try {
            repoRepository.findByOwnerAndName(owner, name)?.downloadCount
        } catch (e: Exception) {
            log.warn("DB downloads lookup failed: {}", e.message); null
        }
        if (fromDb != null && fromDb > 0) return fromDb to false
        return 0L to true
    }

    private suspend fun repoLatestReleaseTag(owner: String, name: String): Pair<String, Boolean> {
        // Fast path: DB.
        val fromDb = try {
            repoRepository.findByOwnerAndName(owner, name)?.latestReleaseTag
        } catch (e: Exception) {
            log.warn("DB release lookup failed: {}", e.message); null
        }
        if (!fromDb.isNullOrBlank()) return fromDb to false

        // Kill switch: skip live GitHub fallback, render a degraded badge.
        if (FeatureFlags.disableBadgeFetch) return "—" to true

        // Fallback: live GitHub fetch via the existing cached resource client.
        val cacheKey = "badge:release:$owner/$name"
        val upstream = "https://api.github.com/repos/$owner/$name/releases/latest"
        return when (val result = resourceClient.fetchCached(cacheKey, upstream, userToken = null, ttlSeconds = 900)) {
            is GitHubResourceClient.Result.Hit -> parseTag(result.body)?.let { it to false } ?: ("—" to true)
            is GitHubResourceClient.Result.StaleFallback -> parseTag(result.body)?.let { it to false } ?: ("—" to true)
            is GitHubResourceClient.Result.NegativeHit, is GitHubResourceClient.Result.UpstreamError -> "—" to true
        }
    }

    private fun parseTag(body: String): String? = try {
        json.decodeFromString(GhRelease.serializer(), body).tag_name?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        log.warn("Failed to parse release JSON: {}", e.message); null
    }

    private suspend fun safeFdroid(): Pair<String, Boolean> {
        return try {
            fdroidCache.getOrFetch { fdroidClient.latestVersionName() } to false
        } catch (e: Exception) {
            log.warn("F-Droid lookup failed: {}", e.message)
            "—" to true
        }
    }

    companion object {
        fun humanizeCount(n: Long): String = when {
            n < 1_000 -> "$n"
            n < 10_000 -> {
                val v = n / 1000.0
                if (v == v.toInt().toDouble()) "${v.toInt()}K"
                else "%.1fK".format(v)
            }
            n < 1_000_000 -> "${n / 1000}K"
            n < 10_000_000 -> {
                val v = n / 1_000_000.0
                if (v == v.toInt().toDouble()) "${v.toInt()}M"
                else "%.1fM".format(v)
            }
            else -> "${n / 1_000_000}M"
        }
    }
}
