package zed.rainxch.githubstore.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class RepoRepository {

    fun findByOwnerAndName(owner: String, name: String): RepoResponse? = transaction {
        Repos.selectAll()
            .where { (Repos.owner eq owner) and (Repos.name eq name) }
            .firstOrNull()
            ?.toRepoResponse()
    }

    fun findByCategory(category: String, platform: String, limit: Int = 50): List<RepoResponse> = transaction {
        Repos.innerJoin(RepoCategories, { id }, { repoId })
            .selectAll()
            .where {
                (RepoCategories.category eq category) and (RepoCategories.platform eq platform)
            }
            .orderBy(RepoCategories.rank to SortOrder.ASC)
            .limit(limit)
            .map { it.toRepoResponse(category = category) }
    }

    fun findByTopicBucket(bucket: String, platform: String, limit: Int = 50): List<RepoResponse> = transaction {
        Repos.innerJoin(RepoTopicBuckets, { id }, { repoId })
            .selectAll()
            .where {
                (RepoTopicBuckets.bucket eq bucket) and (RepoTopicBuckets.platform eq platform)
            }
            .orderBy(RepoTopicBuckets.rank to SortOrder.ASC)
            .limit(limit)
            .map { it.toRepoResponse() }
    }

    private fun ResultRow.toRepoResponse(category: String? = null): RepoResponse {
        val releaseDateStr = this[Repos.latestReleaseDate]?.toString()
        val releaseDate = this[Repos.latestReleaseDate]
        val recencyDays = releaseDate?.let {
            ChronoUnit.DAYS.between(it.toInstant(), OffsetDateTime.now().toInstant()).toInt().coerceAtLeast(0)
        }

        return RepoResponse(
            id = this[Repos.id],
            name = this[Repos.name],
            fullName = this[Repos.fullName],
            owner = RepoOwner(
                login = this[Repos.owner],
                avatarUrl = this[Repos.ownerAvatarUrl],
            ),
            description = this[Repos.description],
            defaultBranch = this[Repos.defaultBranch],
            htmlUrl = this[Repos.htmlUrl],
            stargazersCount = this[Repos.stars],
            forksCount = this[Repos.forks],
            language = this[Repos.language],
            topics = emptyList(), // topics array handled separately when needed
            releasesUrl = "${this[Repos.htmlUrl]}/releases",
            updatedAt = this[Repos.updatedAtGh]?.toString(),
            createdAt = this[Repos.createdAtGh]?.toString(),
            latestReleaseDate = releaseDateStr,
            latestReleaseTag = this[Repos.latestReleaseTag],
            releaseRecency = recencyDays,
            releaseRecencyText = recencyDays?.let { formatRecency(it) },
            trendingScore = if (category == "trending") this[Repos.trendingScore]?.toDouble() else null,
            popularityScore = if (category == "most-popular") this[Repos.popularityScore]?.toDouble() else null,
            hasInstallersAndroid = this[Repos.hasInstallersAndroid],
            hasInstallersWindows = this[Repos.hasInstallersWindows],
            hasInstallersMacos = this[Repos.hasInstallersMacos],
            hasInstallersLinux = this[Repos.hasInstallersLinux],
        )
    }

    private fun formatRecency(days: Int): String = when (days) {
        0 -> "Released today"
        1 -> "Released yesterday"
        else -> "Released $days days ago"
    }
}
