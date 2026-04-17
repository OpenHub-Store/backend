ALTER TABLE search_misses ADD COLUMN IF NOT EXISTS last_processed_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_search_misses_processing
    ON search_misses (miss_count DESC, last_processed_at NULLS FIRST);
