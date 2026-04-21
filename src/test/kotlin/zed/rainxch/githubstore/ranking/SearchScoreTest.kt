package zed.rainxch.githubstore.ranking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchScoreTest {

    @Test
    fun `cold start with zero stars returns zero`() {
        val s = SearchScore.compute(stars = 0)
        assertEquals(0.0, s, 0.0001)
    }

    @Test
    fun `more stars produce higher score when everything else equal`() {
        val low = SearchScore.compute(stars = 100)
        val high = SearchScore.compute(stars = 10_000)
        assertTrue(high > low, "expected high($high) > low($low)")
    }

    @Test
    fun `star contribution saturates at million-star ceiling`() {
        val million = SearchScore.compute(stars = 1_000_000)
        val tenMillion = SearchScore.compute(stars = 10_000_000)
        // log10(1M+1)/6 ≈ 1.0, so the star factor is clamped.
        assertTrue(tenMillion - million < 0.01, "star factor should saturate past 1M")
    }

    @Test
    fun `ctr contribution moves the score`() {
        val baseline = SearchScore.compute(stars = 1000, ctr = 0.0)
        val clicked = SearchScore.compute(stars = 1000, ctr = 1.0)
        // ctr weight is 0.30, so the delta is exactly 0.30 when everything else is 0.
        assertEquals(0.30, clicked - baseline, 0.0001)
    }

    @Test
    fun `install success rate contribution is 20 percent weight`() {
        val baseline = SearchScore.compute(stars = 1000, installSuccessRate = 0.0)
        val perfect = SearchScore.compute(stars = 1000, installSuccessRate = 1.0)
        assertEquals(0.20, perfect - baseline, 0.0001)
    }

    @Test
    fun `fresh release is worth exactly the recency-weight ceiling`() {
        val yearOld = SearchScore.compute(stars = 1000, daysSinceRelease = 1000.0)
        val freshToday = SearchScore.compute(stars = 1000, daysSinceRelease = 0.0)
        // daysSinceRelease = 0 → exp(0) = 1.0 → recency factor = 1.0 → weight 0.10.
        // 1000-day-old → exp(-1000/90) ≈ 0 → recency factor ≈ 0.
        assertEquals(0.10, freshToday - yearOld, 0.0005)
    }

    @Test
    fun `null daysSinceRelease is treated as zero recency contribution`() {
        val nullRelease = SearchScore.compute(stars = 1000, daysSinceRelease = null)
        val ancient    = SearchScore.compute(stars = 1000, daysSinceRelease = 10_000.0)
        // Both should collapse the recency factor to ~0.
        assertTrue(kotlin.math.abs(nullRelease - ancient) < 0.001)
    }

    @Test
    fun `score is bounded in zero to one`() {
        val maxed = SearchScore.compute(
            stars = 10_000_000,
            ctr = 1.0,
            installSuccessRate = 1.0,
            daysSinceRelease = 0.0,
        )
        assertTrue(maxed in 0.0..1.0, "expected 0 <= $maxed <= 1")

        val zeroed = SearchScore.compute(stars = 0)
        assertTrue(zeroed in 0.0..1.0)
    }
}
