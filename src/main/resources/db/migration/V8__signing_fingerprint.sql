-- V8: signing_fingerprint table for E1 external-app match.
--
-- Maps Android signing-cert SHA-256 fingerprints to the upstream GitHub
-- repo that ships the matching binary. Seeded daily from the F-Droid
-- index (cert, source-code-URL) pairs filtered to GitHub URLs.
--
-- Two consumers:
--   1. POST /v1/external-match — fingerprint match path (confidence 0.92)
--   2. GET  /v1/signing-seeds  — paginated dump for client-side local seed table

CREATE TABLE IF NOT EXISTS signing_fingerprint (
    fingerprint   TEXT NOT NULL,
    owner         TEXT NOT NULL,
    repo          TEXT NOT NULL,
    observed_at   BIGINT NOT NULL,
    PRIMARY KEY (fingerprint, owner, repo)
);

-- Pagination cursor uses observed_at as the seek-key. This index is what
-- makes the cursor scan O(log n) instead of full-table.
CREATE INDEX IF NOT EXISTS signing_fingerprint_observed_at_idx
    ON signing_fingerprint (observed_at);

-- Lookup by fingerprint is the hot path during /v1/external-match. A repo
-- can ship under multiple forks / signing keys; the (fingerprint) prefix
-- of the PK already covers it, but a dedicated index keeps it cheap even
-- if the table grows past expected size.
CREATE INDEX IF NOT EXISTS signing_fingerprint_fingerprint_idx
    ON signing_fingerprint (fingerprint);
