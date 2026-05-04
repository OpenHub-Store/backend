package zed.rainxch.githubstore.ingest

import kotlinx.coroutines.runBlocking
import zed.rainxch.githubstore.ingest.GitHubSearchClient.RefreshResult
import zed.rainxch.githubstore.ingest.GitHubSearchClient.RepoWithRelease
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepoRefreshCoordinatorTest {

    private fun fakeRepo(fullName: String = "foo/bar"): GitHubRepo = GitHubRepo(
        id = 42L,
        name = fullName.substringAfter('/'),
        fullName = fullName,
        owner = GitHubOwner(login = fullName.substringBefore('/'), avatarUrl = null),
        htmlUrl = "https://github.com/$fullName",
    )

    private fun fakeRepoWithRelease(fullName: String = "foo/bar"): RepoWithRelease =
        RepoWithRelease(
            repo = fakeRepo(fullName),
            release = GitHubRelease(
                tagName = "v1.0.0",
                publishedAt = "2026-01-01T00:00:00Z",
                assets = listOf(GitHubAsset(name = "app.apk", size = 1000, downloadCount = 5)),
            ),
            platformFlags = mapOf("android" to true),
            downloadCount = 5,
        )

    @Test
    fun `first call returns Ok and persists`() = runBlocking {
        val refreshCalls = AtomicInteger(0)
        val persistCalls = AtomicInteger(0)
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { _, _ ->
                refreshCalls.incrementAndGet()
                RefreshResult.Ok(fakeRepoWithRelease())
            },
            persistFn = { persistCalls.incrementAndGet() },
            cooldownSeconds = 30L,
            budgetPerHour = 100,
        )
        val outcome = coord.refresh("foo", "bar", null)
        assertTrue(outcome is RepoRefreshCoordinator.Outcome.Ok)
        assertEquals(true, outcome.metadataPersisted)
        assertEquals(1, refreshCalls.get())
        assertEquals(1, persistCalls.get())
    }

    @Test
    fun `second call within cooldown returns Cooldown`() = runBlocking {
        val refreshCalls = AtomicInteger(0)
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { _, _ ->
                refreshCalls.incrementAndGet()
                RefreshResult.Ok(fakeRepoWithRelease())
            },
            persistFn = { },
            cooldownSeconds = 30L,
            budgetPerHour = 100,
        )
        coord.refresh("foo", "bar", null)
        val second = coord.refresh("foo", "bar", null)
        assertTrue(second is RepoRefreshCoordinator.Outcome.Cooldown)
        assertTrue(second.retryAfterSeconds in 1..30)
        assertEquals(1, refreshCalls.get(), "upstream must not be called during cooldown")
    }

    @Test
    fun `cooldown is per-repo, different repos do not block each other`() = runBlocking {
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { name, _ -> RefreshResult.Ok(fakeRepoWithRelease(name)) },
            persistFn = { },
            cooldownSeconds = 60L,
            budgetPerHour = 100,
        )
        val a = coord.refresh("foo", "bar", null)
        val b = coord.refresh("baz", "qux", null)
        assertTrue(a is RepoRefreshCoordinator.Outcome.Ok)
        assertTrue(b is RepoRefreshCoordinator.Outcome.Ok)
    }

    @Test
    fun `cooldown is case-insensitive on owner-name pair`() = runBlocking {
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { _, _ -> RefreshResult.Ok(fakeRepoWithRelease()) },
            persistFn = { },
            cooldownSeconds = 60L,
            budgetPerHour = 100,
        )
        coord.refresh("Foo", "Bar", null)
        val second = coord.refresh("foo", "bar", null)
        assertTrue(second is RepoRefreshCoordinator.Outcome.Cooldown)
    }

    @Test
    fun `budget exhausted returns BudgetExhausted`() = runBlocking {
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { name, _ -> RefreshResult.Ok(fakeRepoWithRelease(name)) },
            persistFn = { },
            // 0 cooldown lets us race the budget without per-repo blocking.
            cooldownSeconds = 0L,
            budgetPerHour = 2,
        )
        val a = coord.refresh("a", "x", null)
        val b = coord.refresh("b", "y", null)
        val c = coord.refresh("c", "z", null)
        assertTrue(a is RepoRefreshCoordinator.Outcome.Ok)
        assertTrue(b is RepoRefreshCoordinator.Outcome.Ok)
        assertTrue(c is RepoRefreshCoordinator.Outcome.BudgetExhausted)
    }

    @Test
    fun `Gone result maps to NotFound`() = runBlocking {
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { _, _ -> RefreshResult.Gone },
            persistFn = { },
            cooldownSeconds = 30L,
            budgetPerHour = 100,
        )
        val outcome = coord.refresh("foo", "bar", null)
        assertTrue(outcome is RepoRefreshCoordinator.Outcome.NotFound)
    }

    @Test
    fun `Archived result maps to Archived`() = runBlocking {
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { _, _ -> RefreshResult.Archived },
            persistFn = { },
            cooldownSeconds = 30L,
            budgetPerHour = 100,
        )
        val outcome = coord.refresh("foo", "bar", null)
        assertTrue(outcome is RepoRefreshCoordinator.Outcome.Archived)
    }

    @Test
    fun `NoUsableRelease returns Ok without persisting`() = runBlocking {
        val persistCalls = AtomicInteger(0)
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { _, _ -> RefreshResult.NoUsableRelease(fakeRepo()) },
            persistFn = { persistCalls.incrementAndGet() },
            cooldownSeconds = 30L,
            budgetPerHour = 100,
        )
        val outcome = coord.refresh("foo", "bar", null)
        assertTrue(outcome is RepoRefreshCoordinator.Outcome.Ok)
        assertEquals(false, outcome.metadataPersisted)
        assertEquals(0, persistCalls.get(), "metadata-only path must not persist")
    }

    @Test
    fun `TransientFailure maps to UpstreamError`() = runBlocking {
        val coord = RepoRefreshCoordinator(
            refreshUpstream = { _, _ -> RefreshResult.TransientFailure },
            persistFn = { },
            cooldownSeconds = 30L,
            budgetPerHour = 100,
        )
        val outcome = coord.refresh("foo", "bar", null)
        assertTrue(outcome is RepoRefreshCoordinator.Outcome.UpstreamError)
    }
}
