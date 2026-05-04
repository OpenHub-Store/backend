package zed.rainxch.githubstore.ingest

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Coordinates user-triggered repo refreshes. Two safety nets:
//
//   1. Per-repo cooldown -- one refresh per (owner, name) every N seconds
//      regardless of caller. Stops a single client (or coordinated set)
//      from spam-clicking refresh on the same repo to torch pool tokens.
//
//   2. Global hourly budget -- caps total refreshes/hour across all repos.
//      Even if every popular repo is refreshed at its cooldown ceiling,
//      this bounds pool consumption to a knowable share of the 4-PAT
//      aggregate quota.
//
// The cooldown timestamp is recorded BEFORE the upstream call returns, so
// concurrent refresh requests for the same repo see the stamp and back off
// rather than racing to the same upstream call.
// `internal` because the constructor exposes GitHubSearchClient.RefreshResult
// and RepoWithRelease, both of which are internal. The whole class lives in
// the ingest package; Koin DI + the routes that wire it are in the same
// module, so internal visibility is sufficient.
internal class RepoRefreshCoordinator(
    // Lambdas instead of a direct GitHubSearchClient dep so tests can
    // exercise the cooldown / budget gates without standing up a real Ktor
    // HttpClient against api.github.com. AppModule wires both to the
    // matching GitHubSearchClient methods.
    private val refreshUpstream: suspend (fullName: String, userToken: String?) -> GitHubSearchClient.RefreshResult,
    private val persistFn: (GitHubSearchClient.RepoWithRelease) -> Unit,
    private val cooldownSeconds: Long = 30L,
    private val budgetPerHour: Int = 1000,
) {
    private val log = LoggerFactory.getLogger(RepoRefreshCoordinator::class.java)

    // Last successful or attempted refresh per repo. Bounded growth: entries
    // older than 1h are pruned opportunistically; the map stays small for any
    // realistic active-repo set.
    private val lastRefreshAt = ConcurrentHashMap<String, Instant>()

    // Rolling hourly budget. Window resets when the first refresh after
    // 60min from the previous start fires.
    private val budgetCounter = AtomicInteger(0)
    private val budgetWindowStart = AtomicReference(Instant.now())

    sealed class Outcome {
        // Refreshed successfully. metadataRefreshed=true means we wrote the
        // latest GitHub data to Postgres + Meili. false means the repo was
        // fetched but had no usable release (still surface metadata to the
        // client, just don't persist a partial row).
        data class Ok(val repo: GitHubRepo, val metadataPersisted: Boolean) : Outcome()
        data class Cooldown(val retryAfterSeconds: Long) : Outcome()
        data class BudgetExhausted(val retryAfterSeconds: Long) : Outcome()
        data object NotFound : Outcome()
        data object Archived : Outcome()
        data object UpstreamError : Outcome()
    }

    suspend fun refresh(owner: String, name: String, userToken: String?): Outcome {
        val key = "$owner/$name".lowercase()
        val now = Instant.now()

        // Atomic cooldown claim. Two concurrent callers can both read an
        // expired (or absent) timestamp and both write `now` if the read,
        // check, and write are separate steps -- a TOCTOU race that lets
        // both calls pass the gate and fire two upstream requests for one
        // user click. ConcurrentHashMap.compute serialises the inspect +
        // update under the per-bin lock, so only the first caller wins
        // the claim; the second sees the just-written timestamp and falls
        // into the cooldown branch.
        //
        // The claim runs BEFORE the budget gate so a budget-exhausted
        // request still occupies its cooldown slot. Without that, a
        // spam-retry pattern could bypass cooldown entirely by relying on
        // budget rejections (which leave the slot free).
        var cooldownRemainder: Long? = null
        lastRefreshAt.compute(key) { _, existing ->
            if (existing != null) {
                val secondsSince = Duration.between(existing, now).seconds
                if (secondsSince < cooldownSeconds) {
                    cooldownRemainder = cooldownSeconds - secondsSince
                    existing
                } else {
                    now
                }
            } else {
                now
            }
        }
        cooldownRemainder?.let { return Outcome.Cooldown(retryAfterSeconds = it) }

        // Budget gate. Reset the window first if it's stale.
        rotateBudgetIfStale(now)
        val nextCount = budgetCounter.incrementAndGet()
        if (nextCount > budgetPerHour) {
            budgetCounter.decrementAndGet()
            val secondsUntilReset = budgetSecondsUntilReset(now)
            return Outcome.BudgetExhausted(retryAfterSeconds = secondsUntilReset)
        }

        pruneStaleEntriesOpportunistically(now)

        return when (val result = refreshUpstream("$owner/$name", userToken)) {
            is GitHubSearchClient.RefreshResult.Ok -> {
                persistFn(result.repo)
                log.info("Refreshed repo {}/{} (persisted)", owner, name)
                Outcome.Ok(repo = result.repo.repo, metadataPersisted = true)
            }
            is GitHubSearchClient.RefreshResult.NoUsableRelease -> {
                // Repo metadata fetched cleanly, but it has no installable
                // release. Surface metadata to the client; skip persist
                // because the DB layout requires release info.
                log.info("Refreshed repo {}/{} (metadata-only, no usable release)", owner, name)
                Outcome.Ok(repo = result.repo, metadataPersisted = false)
            }
            GitHubSearchClient.RefreshResult.Gone -> Outcome.NotFound
            GitHubSearchClient.RefreshResult.Archived -> Outcome.Archived
            GitHubSearchClient.RefreshResult.TransientFailure -> Outcome.UpstreamError
        }
    }

    private fun rotateBudgetIfStale(now: Instant) {
        val start = budgetWindowStart.get()
        if (Duration.between(start, now).toMinutes() >= 60) {
            // CAS so a parallel rotation doesn't drop a fresh window mid-flight.
            if (budgetWindowStart.compareAndSet(start, now)) {
                budgetCounter.set(0)
            }
        }
    }

    private fun budgetSecondsUntilReset(now: Instant): Long {
        val start = budgetWindowStart.get()
        val elapsed = Duration.between(start, now).seconds
        return (3600L - elapsed).coerceAtLeast(1L)
    }

    private fun pruneStaleEntriesOpportunistically(now: Instant) {
        // Cheap O(n) sweep on each successful refresh. The map's working set
        // is "actively-viewed repos in the last hour" -- bounded by realistic
        // user behaviour. No background cleanup thread needed.
        val cutoff = now.minus(Duration.ofHours(1))
        lastRefreshAt.entries.removeIf { it.value.isBefore(cutoff) }
    }

    // For tests + /internal/metrics.
    internal fun budgetUsed(): Int = budgetCounter.get()
}
