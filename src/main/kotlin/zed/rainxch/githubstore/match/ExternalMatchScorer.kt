package zed.rainxch.githubstore.match

// Pure scoring logic for the search-strategy path of /v1/external-match.
// Takes a candidate's identifying info plus a search hit and returns a
// confidence in [0.0, 0.85] — search-only confidence is capped below the
// auto-link threshold (0.85) by spec, since search is the weakest signal.
//
// Scoring formula transcribed from roadmap/E1_BACKEND_HANDOFF.md §1:
//   exact-name match            +0.40
//   substring match              +0.20
//   owner login matches the
//     packageName author segment +0.20
//   star bucket
//     >= 1000 stars              +0.15
//     >= 100 stars               +0.10
//     >= 10 stars                +0.05
//   has APK assets in last
//     5 releases                 +0.10
//   no APK assets in last
//     5 releases                 -0.20  (heavy penalty)
//   description contains
//     "android" or "apk"         +0.05
// Cap at 0.85 — keeps search hits out of the auto-link tier.
object ExternalMatchScorer {

    private const val MAX_SEARCH_CONFIDENCE = 0.85

    data class SearchHit(
        val owner: String,
        val repo: String,
        val stars: Int,
        val description: String?,
        val hasAndroidAssetInRecentReleases: Boolean,
    )

    /**
     * @param packageName  the Android package name (e.g. "com.example.foo")
     * @param appLabel     human-readable label (e.g. "Foo App")
     * @param hit          a single GitHub search result being scored
     */
    fun score(
        packageName: String,
        appLabel: String,
        hit: SearchHit,
    ): Double {
        var confidence = 0.0

        val repoLower = hit.repo.lowercase()
        val labelLower = appLabel.lowercase()
        val labelTokens = tokenize(appLabel)
        val repoTokens = tokenize(hit.repo)

        // Name match: exact (token-set equality, e.g. "Foo App" ↔ "foo-app")
        // beats substring beats nothing.
        when {
            repoTokens.isNotEmpty() && repoTokens == labelTokens -> confidence += 0.40
            repoLower.contains(labelLower) || labelLower.contains(repoLower) -> confidence += 0.20
        }

        // Owner match: the packageName author segment (the second-from-end
        // segment of a reverse-DNS package name) often equals the GitHub owner.
        // E.g. com.octocat.foo → author segment "octocat" → matches owner "octocat".
        val authorSegment = packageName.split('.').dropLast(1).lastOrNull()
        if (authorSegment != null && authorSegment.equals(hit.owner, ignoreCase = true)) {
            confidence += 0.20
        }

        // Stars bucket — additive, single tier.
        confidence += when {
            hit.stars >= 1_000 -> 0.15
            hit.stars >= 100 -> 0.10
            hit.stars >= 10 -> 0.05
            else -> 0.0
        }

        // Asset signal — heavy penalty for no Android assets, since it strongly
        // indicates the repo isn't actually shipping the binary the user has
        // installed (e.g. it's a library, demo, or wrong fork).
        confidence += if (hit.hasAndroidAssetInRecentReleases) 0.10 else -0.20

        // Description contains "android" or "apk" — small signal that the repo
        // is meant to ship Android binaries.
        val desc = hit.description?.lowercase().orEmpty()
        if (desc.contains("android") || desc.contains("apk")) {
            confidence += 0.05
        }

        return confidence.coerceIn(0.0, MAX_SEARCH_CONFIDENCE)
    }

    /**
     * Score every hit and return the top N (default 5) sorted by confidence
     * descending, ties broken by stars descending. Filters out scores ≤ 0
     * since a negative score means "actively wrong fork."
     */
    fun rank(
        packageName: String,
        appLabel: String,
        hits: List<SearchHit>,
        limit: Int = 5,
    ): List<Pair<SearchHit, Double>> =
        hits.map { it to score(packageName, appLabel, it) }
            .filter { (_, c) -> c > 0.0 }
            .sortedWith(compareByDescending<Pair<SearchHit, Double>> { it.second }
                .thenByDescending { it.first.stars })
            .take(limit)

    /** Lowercase + split on non-alphanumeric → set of tokens. */
    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .toSet()
}
