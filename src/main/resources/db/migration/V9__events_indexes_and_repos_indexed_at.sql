-- V9: tighter indexes for the worker hot paths.
--
-- 1) Replace the lone idx_events_repo (repo_id) with a composite (repo_id, ts)
--    partial index that matches SignalAggregationWorker's window-aggregate
--    query: "events for repo X newer than cutoff". The standalone repo_id
--    index forced a heap scan to filter by ts; the composite covers both.
--
-- 2) Add idx_repos_indexed_at to support RepoRefreshWorker's "oldest first"
--    pickOldest scan. Today it sequential-scans the repos table; will hurt
--    badly past 30k rows. NULLS FIRST so freshly-passthrough'd rows with no
--    prior indexed_at sort to the top of the refresh queue.

DROP INDEX IF EXISTS idx_events_repo;

CREATE INDEX IF NOT EXISTS idx_events_repo_ts
    ON events (repo_id, ts) WHERE repo_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_repos_indexed_at
    ON repos (indexed_at NULLS FIRST);
