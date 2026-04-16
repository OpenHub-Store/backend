package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class EventRequest(
    val deviceId: String,
    val platform: String,
    val appVersion: String? = null,
    val eventType: String,
    val repoId: Long? = null,
    val queryHash: String? = null,
    val resultCount: Int? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
)
