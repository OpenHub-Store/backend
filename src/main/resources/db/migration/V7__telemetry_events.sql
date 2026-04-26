-- V7: telemetry_events table for E6 (Telemetry Foundation).
--
-- Distinct from the existing `events` table which feeds the ranking signal
-- pipeline. Telemetry is product-metric oriented: ephemeral session_id (reset
-- per app launch), opt-out by user choice, schema-allowlisted server-side.
--
-- Indexes are time-series shaped: queries are typically "events of name X in
-- the last N hours" or "session-funnel for session_id S".

CREATE TABLE IF NOT EXISTS telemetry_events (
    id           BIGSERIAL PRIMARY KEY,
    ts           TIMESTAMPTZ NOT NULL,
    name         TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    platform     TEXT,
    app_version  TEXT,
    -- props stored as TEXT containing JSON. JSONB would buy us indexed
    -- key-extraction at write time, but for Week-1 telemetry every consumer
    -- of `props` is a future operator dashboard query — fine to parse on
    -- read. Convert to JSONB later if a hot dashboard needs key indexing.
    props        TEXT NOT NULL DEFAULT '{}',
    received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS telemetry_events_ts_idx
    ON telemetry_events (ts DESC);

CREATE INDEX IF NOT EXISTS telemetry_events_name_ts_idx
    ON telemetry_events (name, ts DESC);

CREATE INDEX IF NOT EXISTS telemetry_events_session_ts_idx
    ON telemetry_events (session_id, ts DESC);
