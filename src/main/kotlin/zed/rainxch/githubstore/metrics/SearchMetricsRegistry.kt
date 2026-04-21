package zed.rainxch.githubstore.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory counters for the search pipeline. Reset on process restart — this is
 * a lightweight dashboard aid, not durable telemetry. The durable signal is the
 * Events / SearchMisses / RepoSignals tables.
 */
class SearchMetricsRegistry {
    private val meiliOnlyCount = AtomicLong(0)
    private val passthroughCount = AtomicLong(0)
    private val postgresFallbackCount = AtomicLong(0)
    private val exploreCount = AtomicLong(0)
    private val zeroResultCount = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)
    private val totalLatencySamples = AtomicLong(0)
    private val startedAtMs = System.currentTimeMillis()

    fun recordMeiliOnly(resultCount: Int, elapsedMs: Int) {
        meiliOnlyCount.incrementAndGet()
        record(resultCount, elapsedMs)
    }

    fun recordPassthrough(resultCount: Int, elapsedMs: Int) {
        passthroughCount.incrementAndGet()
        record(resultCount, elapsedMs)
    }

    fun recordPostgresFallback(resultCount: Int, elapsedMs: Int) {
        postgresFallbackCount.incrementAndGet()
        record(resultCount, elapsedMs)
    }

    // User-triggered "search more from GitHub" — separate from passthrough
    // (which is backend-triggered when Meili returns <5). Tracked as its own
    // counter so the dashboard can show explicit user exploration volume.
    fun recordExplore(resultCount: Int, elapsedMs: Int) {
        exploreCount.incrementAndGet()
        record(resultCount, elapsedMs)
    }

    private fun record(resultCount: Int, elapsedMs: Int) {
        if (resultCount == 0) zeroResultCount.incrementAndGet()
        totalLatencyMs.addAndGet(elapsedMs.toLong())
        totalLatencySamples.incrementAndGet()
    }

    fun snapshot(): Snapshot {
        val samples = totalLatencySamples.get().coerceAtLeast(1)
        return Snapshot(
            uptimeSeconds = (System.currentTimeMillis() - startedAtMs) / 1000,
            meiliOnly = meiliOnlyCount.get(),
            passthrough = passthroughCount.get(),
            postgresFallback = postgresFallbackCount.get(),
            explore = exploreCount.get(),
            zeroResult = zeroResultCount.get(),
            avgLatencyMs = totalLatencyMs.get() / samples,
        )
    }

    data class Snapshot(
        val uptimeSeconds: Long,
        val meiliOnly: Long,
        val passthrough: Long,
        val postgresFallback: Long,
        val explore: Long,
        val zeroResult: Long,
        val avgLatencyMs: Long,
    )
}
