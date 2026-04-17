package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class ExploreResponse(
    val items: List<RepoResponse>,
    val page: Int,
    val hasMore: Boolean,
)
