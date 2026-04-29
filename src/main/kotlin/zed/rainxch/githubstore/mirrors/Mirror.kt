package zed.rainxch.githubstore.mirrors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MirrorType {
    @SerialName("official") OFFICIAL,
    @SerialName("community") COMMUNITY,
}

@Serializable
enum class MirrorStatus {
    @SerialName("ok") OK,
    @SerialName("degraded") DEGRADED,
    @SerialName("down") DOWN,
    @SerialName("unknown") UNKNOWN,
}

// Ping URL is baked in per preset rather than computed at probe time. Direct
// GitHub pings api.github.com/zen (tiny canary). Community mirrors ping a
// known-stable release-asset checksum file at cli/cli@v2.40.0 -- pinned
// release means the URL won't 404 on us. Range: bytes=0-0 keeps actual
// transfer at 1 byte regardless of whether the mirror honors the Range header.
data class MirrorPreset(
    val id: String,
    val name: String,
    val urlTemplate: String?,
    val type: MirrorType,
    val pingUrl: String,
)

// Hardcoded catalog. Adding/removing a mirror is a code change + deploy --
// appropriate friction for a vetted addition. Status is per-mirror runtime
// data and lives in MirrorStatusRegistry, not here.
//
// Order matters: clients render the picker in this order. Direct first
// (always works for non-CN users), then ghfast.top + moeyy.xyz (most
// reliable per the April 2026 landscape research), then the long tail.
object MirrorPresets {

    private const val PROBE_ASSET =
        "https://github.com/cli/cli/releases/download/v2.40.0/gh_2.40.0_checksums.txt"

    val ALL: List<MirrorPreset> = listOf(
        MirrorPreset(
            id = "direct",
            name = "Direct GitHub",
            urlTemplate = null,
            type = MirrorType.OFFICIAL,
            pingUrl = "https://api.github.com/zen",
        ),
        MirrorPreset(
            id = "ghfast_top",
            name = "ghfast.top",
            urlTemplate = "https://ghfast.top/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://ghfast.top/$PROBE_ASSET",
        ),
        MirrorPreset(
            id = "moeyy_xyz",
            name = "github.moeyy.xyz",
            urlTemplate = "https://github.moeyy.xyz/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://github.moeyy.xyz/$PROBE_ASSET",
        ),
        MirrorPreset(
            id = "gh_proxy_com",
            name = "gh-proxy.com",
            urlTemplate = "https://gh-proxy.com/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://gh-proxy.com/$PROBE_ASSET",
        ),
        MirrorPreset(
            id = "ghps_cc",
            name = "ghps.cc",
            urlTemplate = "https://ghps.cc/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://ghps.cc/$PROBE_ASSET",
        ),
        MirrorPreset(
            id = "gh_99988866_xyz",
            name = "gh.api.99988866.xyz",
            urlTemplate = "https://gh.api.99988866.xyz/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://gh.api.99988866.xyz/$PROBE_ASSET",
        ),
    )

    fun byId(id: String): MirrorPreset? = ALL.firstOrNull { it.id == id }
}

@Serializable
data class MirrorListResponse(
    val mirrors: List<MirrorEntry>,
    @SerialName("generated_at") val generatedAt: String,
)

@Serializable
data class MirrorEntry(
    val id: String,
    val name: String,
    @SerialName("url_template") val urlTemplate: String?,
    val type: MirrorType,
    val status: MirrorStatus,
    @SerialName("latency_ms") val latencyMs: Long?,
    @SerialName("last_checked_at") val lastCheckedAt: String?,
)
