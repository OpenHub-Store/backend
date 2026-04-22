-- Generic key-value cache for backend-proxied GitHub resources (releases,
-- readme, user profile, repo-metadata-on-miss). Each row is opaque to us:
-- we just store the upstream body, its ETag, and when we last talked to
-- GitHub so we can do If-None-Match revalidation on stale entries.
--
-- Postgres TOAST auto-compresses TEXT > 2KB, so no manual gzip is needed
-- for READMEs.
CREATE TABLE IF NOT EXISTS resource_cache (
    cache_key      TEXT PRIMARY KEY,          -- "releases:owner/name?page=1&per_page=30"
    body           TEXT NOT NULL,             -- verbatim upstream body (JSON or markdown)
    etag           TEXT,                      -- GitHub's ETag for If-None-Match revalidation
    status         INTEGER NOT NULL,          -- HTTP status code from upstream (200 for hit, 404/451/etc. for negative cache)
    content_type   TEXT NOT NULL DEFAULT 'application/json',
    content_bytes  INTEGER NOT NULL DEFAULT 0,
    fetched_at     TIMESTAMPTZ NOT NULL,      -- last time we talked to GitHub for this key (full fetch OR 304 revalidation)
    expires_at     TIMESTAMPTZ NOT NULL       -- after this, we revalidate (or re-fetch if no ETag)
);

-- Eviction sweep looks for entries last touched a long time ago.
CREATE INDEX IF NOT EXISTS idx_resource_cache_fetched_at ON resource_cache (fetched_at);
