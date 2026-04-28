package zed.rainxch.githubstore.match

import kotlinx.serialization.Serializable

@Serializable
data class ManifestHint(
    val owner: String? = null,
    val repo: String? = null,
)

@Serializable
data class ExternalMatchCandidateRequest(
    val packageName: String,
    val appLabel: String,
    val signingFingerprint: String? = null,
    val installerKind: String? = null,
    val manifestHint: ManifestHint? = null,
)

@Serializable
data class ExternalMatchRequest(
    val platform: String,
    val candidates: List<ExternalMatchCandidateRequest>,
)

@Serializable
data class ExternalMatchCandidate(
    val owner: String,
    val repo: String,
    val confidence: Double,
    // "manifest" | "search" | "fingerprint" — see E1_BACKEND_HANDOFF.md §1.
    val source: String,
    val stars: Int? = null,
    val description: String? = null,
)

@Serializable
data class ExternalMatchEntry(
    val packageName: String,
    val candidates: List<ExternalMatchCandidate>,
)

@Serializable
data class ExternalMatchResponse(
    val matches: List<ExternalMatchEntry>,
)
