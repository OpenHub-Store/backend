package zed.rainxch.githubstore.match

import kotlinx.serialization.Serializable

@Serializable
data class SigningSeedRow(
    val fingerprint: String,
    val owner: String,
    val repo: String,
    // Epoch milliseconds. The client uses this as the `since` cursor on the
    // next sync; mixing units silently corrupts incremental fetches. The
    // V8 migration stores this as BIGINT for the same reason.
    val observedAt: Long,
)

@Serializable
data class SigningSeedsResponse(
    val rows: List<SigningSeedRow>,
    val nextCursor: String? = null,
)
