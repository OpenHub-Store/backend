package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val items: List<RepoResponse>,
    val totalHits: Int,
    val processingTimeMs: Int,
    val source: String, // "meilisearch" or "postgres"
)
