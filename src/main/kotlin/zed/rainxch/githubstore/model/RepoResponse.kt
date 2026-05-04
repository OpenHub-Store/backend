package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class RepoOwner(
    val login: String,
    val avatarUrl: String?,
)

@Serializable
data class RepoResponse(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: RepoOwner,
    val description: String?,
    val defaultBranch: String?,
    val htmlUrl: String,
    val stargazersCount: Int,
    val forksCount: Int,
    // Mirrors GitHub's open_issues_count -- includes both open issues AND
    // open PRs (GitHub treats PRs as a kind of issue). Same value as the
    // GitHub website's Issues tab badge.
    val openIssuesCount: Int = 0,
    // GitHub-detected license. Null when the repo has no LICENSE file or
    // when GitHub couldn't classify it. spdxId is the short tag for chip
    // display ("MIT", "GPL-3.0", "Apache-2.0"); name is the human-readable
    // version ("MIT License").
    val licenseSpdxId: String? = null,
    val licenseName: String? = null,
    val language: String?,
    val topics: List<String>,
    val releasesUrl: String?,
    val updatedAt: String?,
    val createdAt: String?,
    val latestReleaseDate: String? = null,
    val latestReleaseTag: String? = null,
    val releaseRecency: Int? = null,
    val releaseRecencyText: String? = null,
    val downloadCount: Long = 0,
    val trendingScore: Double? = null,
    val popularityScore: Double? = null,
    val hasInstallersAndroid: Boolean = false,
    val hasInstallersWindows: Boolean = false,
    val hasInstallersMacos: Boolean = false,
    val hasInstallersLinux: Boolean = false,
)
