package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val postgres: String,
    val meilisearch: String,
    val version: String,
    // Loaded count, not active count -- operators want "did the load
    // succeed", not "how many are visible right now". Empty (0) is a valid
    // state and does NOT mark health as degraded.
    val announcements: Int,
)
