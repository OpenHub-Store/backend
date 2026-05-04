-- V15: track license info on the repos table so the details screen can
-- surface "MIT License" / "GPL-3.0" / etc. without an extra GitHub
-- passthrough call. Both columns nullable -- not every repo has a
-- detected license.
--
-- Two columns instead of one JSONB:
--   * license_spdx_id   -- short tag ("MIT", "GPL-3.0", "Apache-2.0")
--   * license_name      -- full name ("MIT License")
--
-- We skip GitHub's `key` and `url` fields -- the client renders by
-- spdx_id and links to the GitHub repo's LICENSE file directly.
--
-- Backend writes the columns on:
--   * search passthrough ingest
--   * POST /v1/repo/{owner}/{name}/refresh
--   * RepoRefreshWorker hourly cycle
--   * Python fetcher daily run (once that repo wires the field through)
--
-- Idempotent: ADD COLUMN IF NOT EXISTS handles re-runs.

ALTER TABLE repos
    ADD COLUMN IF NOT EXISTS license_spdx_id TEXT;

ALTER TABLE repos
    ADD COLUMN IF NOT EXISTS license_name TEXT;
