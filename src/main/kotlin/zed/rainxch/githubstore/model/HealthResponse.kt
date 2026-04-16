package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val postgres: String,
    val version: String,
)
