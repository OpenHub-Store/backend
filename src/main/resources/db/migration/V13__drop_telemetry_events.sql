-- V13: drop telemetry_events.
--
-- The /v1/telemetry/events pipeline was reverted before any client shipped
-- a consent UI, so this table never carried real user data -- only two
-- synthetic smoke-test rows from the deploy verification window. Dropping
-- it removes the orphan schema, the JSONB column, and the three indexes
-- (telemetry_events_ts_idx, telemetry_events_name_ts_idx,
-- telemetry_events_session_ts_idx) in one shot.
--
-- V7 (table create) and V8 (props text -> jsonb) are also removed from the
-- DatabaseFactory migrations list in the same revert -- a future fresh
-- install will never see this table at all. Leaving them in the list would
-- create the table seconds before V13 drops it again.
--
-- Idempotent: IF EXISTS guard handles re-runs and fresh installs equally.

DROP TABLE IF EXISTS telemetry_events;
