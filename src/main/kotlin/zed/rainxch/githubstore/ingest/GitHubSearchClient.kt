package zed.rainxch.githubstore.ingest

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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
            val repos = searchGitHub(query, limit = 30)
            if (repos.isEmpty()) return emptyList()

            // Check which repos have releases with installers
            val withInstallers = repos.mapNotNull { repo ->
                val releases = fetchLatestRelease(repo.fullName) ?: return@mapNotNull null
                val platformFlags = detectPlatforms(releases)
                if (platformFlags.none { it.value }) return@mapNotNull null

                // Filter by requested platform if specified
                if (platform != null && platformFlags[platform] != true) return@mapNotNull null

                val downloadCount = releases.assets.sumOf { it.downloadCount }
                RepoWithRelease(repo, releases, platformFlags, downloadCount)
            }.take(limit)

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

    private suspend fun searchGitHub(query: String, limit: Int): List<GitHubRepo> {
        val response = client.get("https://api.github.com/search/repositories") {
            parameter("q", "$query stars:>10")
            parameter("sort", "stars")
            parameter("per_page", limit)
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
                mapOf(
                    "id" to r.repo.id,
                    "full_name" to r.repo.fullName,
                    "owner" to r.repo.owner.login,
                    "name" to r.repo.name,
                    "owner_avatar_url" to r.repo.owner.avatarUrl,
                    "description" to r.repo.description,
                    "default_branch" to r.repo.defaultBranch,
                    "html_url" to r.repo.htmlUrl,
                    "stars" to r.repo.stargazersCount,
                    "forks" to r.repo.forksCount,
                    "language" to r.repo.language,
                    "latest_release_date" to r.release.publishedAt,
                    "latest_release_tag" to r.release.tagName,
                    "download_count" to r.downloadCount,
                    "has_installers_android" to (r.platformFlags["android"] ?: false),
                    "has_installers_windows" to (r.platformFlags["windows"] ?: false),
                    "has_installers_macos" to (r.platformFlags["macos"] ?: false),
                    "has_installers_linux" to (r.platformFlags["linux"] ?: false),
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
