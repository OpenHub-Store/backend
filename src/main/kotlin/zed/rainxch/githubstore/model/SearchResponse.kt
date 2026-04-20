package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val items: List<RepoResponse>,
    val totalHits: Int,
    val processingTimeMs: Int,
    // "meilisearch" | "meilisearch+github" | "postgres"
    val source: String,
    // True iff the on-demand GitHub passthrough actually fired for this request.
    // Lets the client distinguish "GitHub has nothing either" (true) from
    // "index is cold — user should tap Explore" (false) when items is empty.
    val passthroughAttempted: Boolean = false,
)
