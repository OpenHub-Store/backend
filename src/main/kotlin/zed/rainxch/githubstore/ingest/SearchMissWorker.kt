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

class SearchMissWorker(
    @Suppress("unused") private val searchMissRepository: SearchMissRepository,
    @Suppress("unused") private val githubSearch: GitHubSearchClient,
) {
    private val log = LoggerFactory.getLogger(SearchMissWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Worker disabled: warming a missed query requires the raw text, but raw
    // queries are no longer persisted (privacy hardening — see V6 migration).
    // Only the SHA-256 hash remains, which is one-way. The miss-tracking pipeline
    // still produces useful operator metrics (how many distinct queries miss,
    // how often) — that observation work doesn't need raw text. Re-enable only
    // if a privacy-safe representation of the query is reintroduced.
    fun start(): Job = scope.launch {
        log.info("SearchMissWorker disabled: raw query persistence removed for privacy")
    }
}
