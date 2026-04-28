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
-- Idempotent via DO block: the IF check skips the entire ALTER chain once
-- props is already JSONB.
--
-- DROP DEFAULT before TYPE: Postgres won't auto-cast the existing default
-- expression text->jsonb. Without the explicit drop, the ALTER fails with
-- "default for column 'props' cannot be cast automatically to type jsonb"
-- and the whole runMigrations() transaction silently rolls back. Order
-- matters: DROP DEFAULT, then TYPE, then SET DEFAULT with the new literal.
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
            ALTER COLUMN props DROP DEFAULT;
        ALTER TABLE telemetry_events
            ALTER COLUMN props TYPE JSONB USING props::jsonb;
        ALTER TABLE telemetry_events
            ALTER COLUMN props SET DEFAULT '{}'::jsonb;
    END IF;
END $$;
