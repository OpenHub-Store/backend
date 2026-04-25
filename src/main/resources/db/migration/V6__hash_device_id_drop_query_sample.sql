-- V6: privacy hardening
--
-- Two changes, both irreversible:
--
-- 1) Drop the search_misses.query_sample column. We were storing the first 100
--    chars of the raw user query in cleartext for the warming worker. The
--    worker is being changed to operate on hashes only; the cleartext column
--    is a privacy risk and goes away.
--
-- 2) Re-hash existing events.device_id rows in place. New writes go through
--    PrivacyHash.hash() at insert time, but rows written before this migration
--    are still raw client-supplied identifiers. The DatabaseFactory runner
--    substitutes :device_id_pepper with the live DEVICE_ID_PEPPER env var
--    before executing this script. If the env var is unset the runner SKIPS
--    this entire file and logs a warning, so the migration is re-runnable.
--
-- Requires the pgcrypto extension for digest(). Standard Postgres images ship
-- with it; we CREATE EXTENSION IF NOT EXISTS for safety. If pgcrypto is
-- unavailable on a target image, install it manually before re-running.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE search_misses DROP COLUMN IF EXISTS query_sample;

UPDATE events
SET device_id = encode(digest(:'device_id_pepper' || device_id, 'sha256'), 'hex')
WHERE device_id !~ '^[0-9a-f]{64}$';
