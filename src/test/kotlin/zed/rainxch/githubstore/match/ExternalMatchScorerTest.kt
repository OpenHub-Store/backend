package zed.rainxch.githubstore.match

import zed.rainxch.githubstore.match.ExternalMatchScorer.SearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalMatchScorerTest {

    private fun hit(
        owner: String = "octocat",
        repo: String = "hello-world",
        stars: Int = 0,
        description: String? = null,
        hasAndroidAssetInRecentReleases: Boolean = true,
    ) = SearchHit(owner, repo, stars, description, hasAndroidAssetInRecentReleases)

    @Test
    fun `exact-name match plus author plus assets plus android description plus 1k stars sums correctly`() {
        // Token equality: "Hello World" ↔ "hello-world" → +0.40
        // packageName "com.octocat.hello" → author "octocat" matches owner → +0.20
        // 1000 stars → +0.15
        // has Android assets → +0.10
        // description contains "android" → +0.05
        // Total = 0.90, clamped to 0.85.
        val confidence = ExternalMatchScorer.score(
            packageName = "com.octocat.hello",
            appLabel = "Hello World",
            hit = hit(stars = 1000, description = "An Android demo app", hasAndroidAssetInRecentReleases = true),
        )
        assertEquals(0.85, confidence, 0.0001)
    }

    @Test
    fun `no APK in recent releases produces a heavy penalty even with name match`() {
        // Token equality (+0.40), but no assets (-0.20), no other bonuses.
        // Net = 0.20.
        val confidence = ExternalMatchScorer.score(
            packageName = "com.unrelated.foo",
            appLabel = "Hello World",
            hit = hit(repo = "hello-world", stars = 5, hasAndroidAssetInRecentReleases = false),
        )
        assertEquals(0.20, confidence, 0.0001)
    }

    @Test
    fun `substring match scores lower than exact match`() {
        // Substring "foo" appears in "foo-android-client" (and vice versa
        // depending on which contains which). Token sets differ so substring
        // path fires. +0.20 for substring, +0.10 for assets. Total 0.30.
        val confidence = ExternalMatchScorer.score(
            packageName = "com.example.foo",
            appLabel = "foo",
            hit = hit(repo = "foo-android-client", stars = 0, hasAndroidAssetInRecentReleases = true),
        )
        assertEquals(0.30, confidence, 0.0001)
    }

    @Test
    fun `negative score is clamped to zero by rank`() {
        // No name match, no author match, 0 stars, no assets (-0.20), no
        // description bonus. Raw score = -0.20, clamps to 0.0 inside score().
        val confidence = ExternalMatchScorer.score(
            packageName = "com.example.foo",
            appLabel = "Bar",
            hit = hit(repo = "completely-different", stars = 0, hasAndroidAssetInRecentReleases = false),
        )
        assertEquals(0.0, confidence, 0.0001)

        // rank() drops zero-score hits.
        val ranked = ExternalMatchScorer.rank(
            packageName = "com.example.foo",
            appLabel = "Bar",
            hits = listOf(
                hit(repo = "completely-different", stars = 0, hasAndroidAssetInRecentReleases = false),
            ),
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `cap of 0_85 is enforced even with every bonus stacked`() {
        // Token equality (0.40) + author (0.20) + 1k stars (0.15) + assets (0.10)
        // + android description (0.05) = 0.90, clamps to 0.85.
        val confidence = ExternalMatchScorer.score(
            packageName = "com.octocat.hello",
            appLabel = "Hello",
            hit = hit(repo = "hello", stars = 5000, description = "android apk", hasAndroidAssetInRecentReleases = true),
        )
        assertEquals(0.85, confidence, 0.0001)
    }

    @Test
    fun `rank returns at most 5 results sorted by confidence descending`() {
        val hits = (1..10).map { i ->
            hit(owner = "u$i", repo = "hello", stars = i * 100, hasAndroidAssetInRecentReleases = true)
        }
        val ranked = ExternalMatchScorer.rank(
            packageName = "com.example.hello",
            appLabel = "Hello",
            hits = hits,
        )
        assertEquals(5, ranked.size)
        // Higher stars → higher confidence (via the bucket)
        assertTrue(ranked[0].second >= ranked[4].second)
    }

    @Test
    fun `tied confidence breaks by stars descending`() {
        // Two hits with identical scoring inputs except stars — same bucket
        // (both 100+) so confidence ties. Star count is the tiebreaker.
        val highStars = hit(owner = "a", repo = "hello", stars = 500, hasAndroidAssetInRecentReleases = true)
        val lowStars = hit(owner = "b", repo = "hello", stars = 200, hasAndroidAssetInRecentReleases = true)
        val ranked = ExternalMatchScorer.rank(
            packageName = "com.x.hello",
            appLabel = "Hello",
            hits = listOf(lowStars, highStars),
        )
        assertEquals("a", ranked[0].first.owner)
        assertEquals("b", ranked[1].first.owner)
    }

    @Test
    fun `blank appLabel returns 0 instead of false-positive substring bonus`() {
        // Without the defensive guard, "".contains("") is true → bogus +0.20.
        // Route validates against blank, but the scorer is a public pure
        // function — guard at the function entry, not at the route.
        val confidence = ExternalMatchScorer.score(
            packageName = "com.example.foo",
            appLabel = "",
            hit = hit(repo = "anything", stars = 9999, hasAndroidAssetInRecentReleases = true),
        )
        assertEquals(0.0, confidence, 0.0001)
    }

    @Test
    fun `blank repo name returns 0`() {
        val confidence = ExternalMatchScorer.score(
            packageName = "com.example.foo",
            appLabel = "Foo",
            hit = hit(repo = "", stars = 9999, hasAndroidAssetInRecentReleases = true),
        )
        assertEquals(0.0, confidence, 0.0001)
    }

    @Test
    fun `confidence stays under 0_85 cap when all signals contribute`() {
        // Defense against accidentally raising the cap. If this ever returns
        // anything > 0.85, the auto-link tier (>= 0.85 in client policy) gets
        // false positives — production data loss risk.
        val confidence = ExternalMatchScorer.score(
            packageName = "com.octocat.hello",
            appLabel = "Hello",
            hit = hit(repo = "hello", stars = 99999, description = "android apk fork", hasAndroidAssetInRecentReleases = true),
        )
        assertTrue(confidence <= 0.85)
    }
}
