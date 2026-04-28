-- V8: convert telemetry_events.props from TEXT to JSONB.
--
-- V7 stored props as TEXT pending a hot-dashboard need. The E6 hardening pass
-- adds per-event allowed-key allowlists and operator dashboards will start
-- key-extracting (e.g. CATEGORY rollups) in the next iteration. JSONB pays for
-- itself once any query does ->/->> on these rows.
--
-- USING props::jsonb succeeds because every existing row was written by
-- TelemetryRepository which encodes via kotlinx Json (always valid JSON), or
-- defaulted to '{}' at the column level.
--
-- Idempotent via DO block: a second run is a no-op because Postgres errors
-- on TYPE-changes when source and target types match, and the IGNORABLE
-- list in DatabaseFactory does not cover that error.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'telemetry_events'
          AND column_name = 'props'
          AND data_type = 'text'
    ) THEN
        ALTER TABLE telemetry_events
            ALTER COLUMN props TYPE JSONB USING props::jsonb;
        ALTER TABLE telemetry_events
            ALTER COLUMN props SET DEFAULT '{}'::jsonb;
    END IF;
END $$;
