-- V11: cut over device_id hashing from sha256(pepper||value) to HmacSha256(key=pepper, value).
-- See PrivacyHash.kt. Existing rows are NOT rehashed -- sha256 is one-way, original
-- device_ids are not recoverable. Pre-V11 and post-V11 events for the same physical
-- device will have different stored device_id hashes; this is acceptable because
-- ranking signals aggregate per-event, not per-device-history.

CREATE TABLE IF NOT EXISTS migration_marker (
    name TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO migration_marker(name)
VALUES ('v11_device_id_hmac_cutover')
ON CONFLICT (name) DO NOTHING;
