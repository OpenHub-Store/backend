package zed.rainxch.githubstore.ingest

import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.SearchMissRepository
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Periodically warms the search index by processing top search misses.
 *
 * Runs hourly. Picks 10 most-missed queries, runs `explore()` on each,
 * and marks them processed. A query becomes re-processable after 7 days
 * (in case new repos matching it have been published since).
 */
class SearchMissWorker(
    private val searchMissRepository: SearchMissRepository,
    private val githubSearch: GitHubSearchClient,
) {
    private val log = LoggerFactory.getLogger(SearchMissWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val batchSize = 10
    private val delayBetweenQueries = 2.minutes
    private val cycleInterval = 1.hours

    fun start(): Job = scope.launch {
        // Initial delay to let the app finish starting
        delay(30.minutes)

        while (true) {
            try {
                processBatch()
            } catch (e: Exception) {
                log.error("Search miss worker cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
        }
    }

    private suspend fun processBatch() {
        val misses = searchMissRepository.topUnprocessed(batchSize)
        if (misses.isEmpty()) {
            log.debug("No unprocessed misses to warm")
            return
        }

        log.info("Processing {} search misses", misses.size)

        for (miss in misses) {
            try {
                val result = githubSearch.explore(
                    query = miss.querySample,
                    platform = null,
                    page = 1,
                )
                log.info(
                    "Warmed miss '{}' (seen {}x): {} new repos discovered",
                    miss.querySample.take(40),
                    miss.missCount,
                    result.items.size,
                )
                searchMissRepository.markProcessed(miss.queryHash)

                // Pace the GitHub API calls — explore() already does release checks per repo
                delay(delayBetweenQueries)
            } catch (e: Exception) {
                log.warn("Failed to warm miss '{}': {}", miss.querySample.take(40), e.message)
            }
        }
    }
}
