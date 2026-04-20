-- Unified ranking score on repos. Written by SignalAggregationWorker;
-- fed into Meilisearch and used as a tie-breaker in Postgres FTS fallback.
ALTER TABLE repos ADD COLUMN IF NOT EXISTS search_score REAL;
CREATE INDEX IF NOT EXISTS idx_repos_search_score
    ON repos (search_score DESC NULLS LAST);

-- Aggregate of behavioral signals per repo, computed hourly from events.
-- Kept separate from repos so aggregation can run in its own transaction
-- without contending with fetcher upserts.
CREATE TABLE IF NOT EXISTS repo_signals (
    repo_id                    BIGINT PRIMARY KEY REFERENCES repos(id) ON DELETE CASCADE,
    click_count_30d            INTEGER     NOT NULL DEFAULT 0,
    view_count_30d             INTEGER     NOT NULL DEFAULT 0,
    install_started_30d        INTEGER     NOT NULL DEFAULT 0,
    install_success_30d        INTEGER     NOT NULL DEFAULT 0,
    install_failed_30d         INTEGER     NOT NULL DEFAULT 0,
    ctr_score                  REAL        NOT NULL DEFAULT 0,
    install_success_rate       REAL        NOT NULL DEFAULT 0,
    last_click_at              TIMESTAMPTZ,
    updated_at                 TIMESTAMPTZ NOT NULL
);

-- Store the result count at miss-log time so the worker can prioritize
-- (0 results ranks higher than 1-4).
ALTER TABLE search_misses ADD COLUMN IF NOT EXISTS result_count INTEGER;
