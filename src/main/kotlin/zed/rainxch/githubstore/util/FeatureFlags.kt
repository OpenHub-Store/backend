package zed.rainxch.githubstore.util

import zed.rainxch.githubstore.db.SearchMissRepository
import java.security.MessageDigest

object FeatureFlags {
    val disableLiveGitHubPassthrough: Boolean
        get() = System.getenv("DISABLE_LIVE_GITHUB_PASSTHROUGH")?.equals("true", ignoreCase = true) == true

    val disableBadgeFetch: Boolean
        get() = disableLiveGitHubPassthrough ||
            (System.getenv("DISABLE_BADGE_FETCH")?.equals("true", ignoreCase = true) == true)
}

// Short opaque tag for log lines that previously printed the raw query.
// Reuses SearchMissRepository.canonicalize so the same query produces the
// same tag across the codebase. SHA-256 truncated to 8 hex chars — enough
// to correlate log lines, not enough to invert.
fun queryHash(query: String): String {
    val canonical = SearchMissRepository.canonicalize(query)
    val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }.take(8)
}
