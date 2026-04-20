package zed.rainxch.githubstore.ranking

import kotlin.math.exp
import kotlin.math.ln

/**
 * The unified search ranking score. Used by:
 *  - SignalAggregationWorker (hourly, with full click/install signals)
 *  - GitHubSearchClient at ingest time (cold-start, signals defaulted to 0)
 *
 * Each factor is clamped to [0, 1] so the total sits in [0, 1]. Weights sum
 * to 1.0 — tune them here, not in callers.
 */
object SearchScore {
    fun compute(
        stars: Int,
        ctr: Double = 0.0,
        installSuccessRate: Double = 0.0,
        daysSinceRelease: Double? = null,
    ): Double {
        val starFactor = (ln((stars + 1).toDouble()) / ln(10.0) / 6.0).coerceIn(0.0, 1.0)
        val recencyFactor = daysSinceRelease?.let { exp(-it / 90.0).coerceIn(0.0, 1.0) } ?: 0.0
        return 0.40 * starFactor + 0.30 * ctr + 0.20 * installSuccessRate + 0.10 * recencyFactor
    }
}
