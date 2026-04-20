package zed.rainxch.githubstore.ingest

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.Repos
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class GitHubSearchClient(
    private val meilisearchClient: zed.rainxch.githubstore.db.MeilisearchClient,
) {
    private val log = LoggerFactory.getLogger(GitHubSearchClient::class.java)

    // Use any available token — the backend doesn't need a dedicated PAT,
    // unauthenticated gets 10 req/min which is enough for on-demand
    private val githubToken: String? = System.getenv("GITHUB_TOKEN")

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val installerExtensions = listOf(
        ".apk", ".aab",                          // Android
        ".exe", ".msi", ".msix",                  // Windows
        ".dmg", ".pkg",                           // macOS
        ".appimage", ".deb", ".rpm", ".flatpak",  // Linux
    )

    // NSFW blocklist — mirrors the Python fetcher's BLOCKED_TOPICS.
    // Applied to query, topics, and description of GitHub search results.
    private val blockedTerms = setOf(
        "nsfw", "porn", "pornography", "hentai", "e-hentai", "ehentai",
        "adult", "adult-content", "xxx", "erotic", "erotica", "sex",
        "nude", "nudes", "nudity", "lewd", "r18", "r-18",
        "rule34", "rule-34", "booru", "gelbooru", "danbooru",
        "nhentai", "hanime", "ecchi", "yaoi", "yuri", "doujin", "doujinshi",
        "onlyfans", "fansly", "chaturbate", "xvideos", "pornhub",
        "xhamster", "xnxx", "redtube", "cam-girl", "camgirl",
        "fetish", "bdsm", "harem", "waifu", "18+",
    )

    private fun queryIsBlocked(query: String): Boolean {
        val lower = query.lowercase()
        return blockedTerms.any { term -> lower.contains(term) }
    }

    private fun repoIsBlocked(repo: GitHubRepo): Boolean {
        val topicsLower = repo.topics.map { it.lowercase() }.toSet()
        if (topicsLower.any { it in blockedTerms }) return true
        val desc = (repo.description ?: "").lowercase()
        return blockedTerms.any { term -> desc.contains(term) }
    }

    /**
     * Search GitHub for repos matching the query, check for installer releases,
     * ingest into Postgres + Meilisearch, and return the results.
     */
    suspend fun searchAndIngest(
        query: String,
        platform: String?,
        limit: Int = 10,
    ): List<RepoResponse> {
        try {
            if (queryIsBlocked(query)) return emptyList()

            val repos = searchGitHub(query, limit = 30, page = 1, minStars = 10)
            if (repos.isEmpty()) return emptyList()

            // Check which repos have releases with installers — fetch in parallel
            val candidates = repos.filterNot { repoIsBlocked(it) }
            val withInstallers = coroutineScope {
                candidates.map { repo ->
                    async {
                        val release = fetchLatestRelease(repo.fullName) ?: return@async null
                        val platformFlags = detectPlatforms(release)
                        if (platformFlags.none { it.value }) return@async null
                        if (platform != null && platformFlags[platform] != true) return@async null
                        val downloadCount = release.assets.sumOf { it.downloadCount }
                        RepoWithRelease(repo, release, platformFlags, downloadCount)
                    }
                }.awaitAll()
            }.filterNotNull().take(limit)

            if (withInstallers.isEmpty()) return emptyList()

            // Ingest into Postgres
            ingestToPostgres(withInstallers)

            // Sync to Meilisearch
            syncToMeilisearch(withInstallers)

            log.info("On-demand ingest: {} repos for query '{}'", withInstallers.size, query)

            return withInstallers.map { it.toRepoResponse() }
        } catch (e: Exception) {
            log.warn("GitHub search passthrough failed for query '{}': {}", query, e.message)
            return emptyList()
        }
    }

    /**
     * Explicit user-triggered "Fetch more from GitHub" — paginated deep search.
     * Returns (new repos added, hasMore flag).
     */
    suspend fun explore(
        query: String,
        platform: String?,
        page: Int,
    ): ExploreResult {
        try {
            if (queryIsBlocked(query)) return ExploreResult(emptyList(), hasMore = false)

            // Fetch 10 repos per page, require 5+ stars to filter abandoned junk
            val repos = searchGitHub(query, limit = 10, page = page, minStars = 5)
            if (repos.isEmpty()) return ExploreResult(emptyList(), hasMore = false)

            val filtered = repos.filter { repo ->
                !repo.archived && !repo.disabled && !repoIsBlocked(repo)
            }

            val withInstallers = coroutineScope {
                filtered.map { repo ->
                    async {
                        val release = fetchLatestRelease(repo.fullName) ?: return@async null
                        val platformFlags = detectPlatforms(release)
                        if (platformFlags.none { it.value }) return@async null
                        if (platform != null && platformFlags[platform] != true) return@async null
                        val downloadCount = release.assets.sumOf { it.downloadCount }
                        RepoWithRelease(repo, release, platformFlags, downloadCount)
                    }
                }.awaitAll()
            }.filterNotNull()

            if (withInstallers.isNotEmpty()) {
                ingestToPostgres(withInstallers)
                syncToMeilisearch(withInstallers)
                log.info("Explore ingest: {} repos for query '{}' page={}", withInstallers.size, query, page)
            }

            // hasMore = true if GitHub returned a full page (more pages may exist)
            val hasMore = repos.size >= 10
            return ExploreResult(withInstallers.map { it.toRepoResponse() }, hasMore = hasMore)
        } catch (e: Exception) {
            log.warn("Explore failed for query '{}' page {}: {}", query, page, e.message)
            return ExploreResult(emptyList(), hasMore = false)
        }
    }

    private suspend fun searchGitHub(
        query: String,
        limit: Int,
        page: Int = 1,
        minStars: Int = 10,
    ): List<GitHubRepo> {
        val response = client.get("https://api.github.com/search/repositories") {
            // fork:true includes both forks and non-forks.
            // Abandoned forks get filtered out later by installer release + star checks.
            parameter("q", "$query stars:>=$minStars fork:true")
            parameter("sort", "stars")
            parameter("per_page", limit)
            parameter("page", page)
            header("Accept", "application/vnd.github+json")
            if (githubToken != null) {
                header("Authorization", "token $githubToken")
            }
        }

        if (response.status != HttpStatusCode.OK) return emptyList()
        return response.body<GitHubSearchResponse>().items
    }

    private suspend fun fetchLatestRelease(fullName: String): GitHubRelease? {
        return try {
            val response = client.get("https://api.github.com/repos/$fullName/releases/latest") {
                header("Accept", "application/vnd.github+json")
                if (githubToken != null) {
                    header("Authorization", "token $githubToken")
                }
            }
            if (response.status == HttpStatusCode.OK) response.body<GitHubRelease>() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun detectPlatforms(release: GitHubRelease): Map<String, Boolean> {
        val assetNames = release.assets.map { it.name.lowercase() }
        return mapOf(
            "android" to assetNames.any { name -> installerExtensions.filter { it in listOf(".apk", ".aab") }.any { name.endsWith(it) } },
            "windows" to assetNames.any { name -> installerExtensions.filter { it in listOf(".exe", ".msi", ".msix") }.any { name.endsWith(it) } },
            "macos" to assetNames.any { name -> installerExtensions.filter { it in listOf(".dmg", ".pkg") }.any { name.endsWith(it) } },
            "linux" to assetNames.any { name -> installerExtensions.filter { it in listOf(".appimage", ".deb", ".rpm", ".flatpak") }.any { name.endsWith(it) } },
        )
    }

    private fun ingestToPostgres(repos: List<RepoWithRelease>) {
        transaction {
            for (r in repos) {
                val repo = r.repo
                val platforms = r.platformFlags
                Repos.upsert(Repos.id) {
                    it[id] = repo.id
                    it[fullName] = repo.fullName
                    it[owner] = repo.owner.login
                    it[name] = repo.name
                    it[ownerAvatarUrl] = repo.owner.avatarUrl
                    it[description] = repo.description
                    it[defaultBranch] = repo.defaultBranch
                    it[htmlUrl] = repo.htmlUrl
                    it[stars] = repo.stargazersCount
                    it[forks] = repo.forksCount
                    it[language] = repo.language
                    it[topics] = repo.topics
                    it[latestReleaseDate] = r.release.publishedAt?.let {
                        try { OffsetDateTime.parse(it) } catch (_: Exception) { null }
                    }
                    it[latestReleaseTag] = r.release.tagName
                    it[hasInstallersAndroid] = platforms["android"] ?: false
                    it[hasInstallersWindows] = platforms["windows"] ?: false
                    it[hasInstallersMacos] = platforms["macos"] ?: false
                    it[hasInstallersLinux] = platforms["linux"] ?: false
                    it[downloadCount] = r.downloadCount
                    it[indexedAt] = OffsetDateTime.now()
                }
            }
        }
    }

    private suspend fun syncToMeilisearch(repos: List<RepoWithRelease>) {
        try {
            val docs = repos.map { r ->
                zed.rainxch.githubstore.db.MeiliRepoHit(
                    id = r.repo.id,
                    full_name = r.repo.fullName,
                    owner = r.repo.owner.login,
                    name = r.repo.name,
                    owner_avatar_url = r.repo.owner.avatarUrl,
                    description = r.repo.description,
                    default_branch = r.repo.defaultBranch,
                    html_url = r.repo.htmlUrl,
                    stars = r.repo.stargazersCount,
                    forks = r.repo.forksCount,
                    language = r.repo.language,
                    topics = r.repo.topics,
                    latest_release_date = r.release.publishedAt,
                    latest_release_tag = r.release.tagName,
                    download_count = r.downloadCount,
                    has_installers_android = r.platformFlags["android"] ?: false,
                    has_installers_windows = r.platformFlags["windows"] ?: false,
                    has_installers_macos = r.platformFlags["macos"] ?: false,
                    has_installers_linux = r.platformFlags["linux"] ?: false,
                )
            }
            meilisearchClient.addDocuments(docs)
        } catch (e: Exception) {
            log.warn("Failed to sync on-demand repos to Meilisearch: {}", e.message)
        }
    }

    private data class RepoWithRelease(
        val repo: GitHubRepo,
        val release: GitHubRelease,
        val platformFlags: Map<String, Boolean>,
        val downloadCount: Long = 0,
    ) {
        fun toRepoResponse(): RepoResponse {
            val releaseDateStr = release.publishedAt
            val recencyDays = releaseDateStr?.let {
                try {
                    val rd = OffsetDateTime.parse(it)
                    ChronoUnit.DAYS.between(rd.toInstant(), OffsetDateTime.now().toInstant()).toInt().coerceAtLeast(0)
                } catch (_: Exception) { null }
            }

            return RepoResponse(
                id = repo.id,
                name = repo.name,
                fullName = repo.fullName,
                owner = RepoOwner(login = repo.owner.login, avatarUrl = repo.owner.avatarUrl),
                description = repo.description,
                defaultBranch = repo.defaultBranch,
                htmlUrl = repo.htmlUrl,
                stargazersCount = repo.stargazersCount,
                forksCount = repo.forksCount,
                language = repo.language,
                topics = repo.topics,
                releasesUrl = "${repo.htmlUrl}/releases",
                updatedAt = repo.updatedAt,
                createdAt = repo.createdAt,
                latestReleaseDate = releaseDateStr,
                latestReleaseTag = release.tagName,
                releaseRecency = recencyDays,
                releaseRecencyText = recencyDays?.let {
                    when (it) {
                        0 -> "Released today"
                        1 -> "Released yesterday"
                        else -> "Released $it days ago"
                    }
                },
                downloadCount = downloadCount,
                hasInstallersAndroid = platformFlags["android"] ?: false,
                hasInstallersWindows = platformFlags["windows"] ?: false,
                hasInstallersMacos = platformFlags["macos"] ?: false,
                hasInstallersLinux = platformFlags["linux"] ?: false,
            )
        }
    }
}

// GitHub API DTOs

data class ExploreResult(
    val items: List<RepoResponse>,
    val hasMore: Boolean,
)

@Serializable
data class GitHubSearchResponse(
    val items: List<GitHubRepo> = emptyList(),
)

@Serializable
data class GitHubRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val owner: GitHubOwner,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("stargazers_count") val stargazersCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    val language: String? = null,
    val topics: List<String> = emptyList(),
    val archived: Boolean = false,
    val disabled: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class GitHubOwner(
    val login: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("download_count") val downloadCount: Long = 0,
)
