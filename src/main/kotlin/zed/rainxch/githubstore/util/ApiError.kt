package zed.rainxch.githubstore.util

import kotlinx.serialization.Serializable

// `error` key matches the existing wire shape (`mapOf("error" to ...)`),
// so clients that string-match on it keep working unchanged.
@Serializable
data class ApiError(val error: String, val message: String? = null)
