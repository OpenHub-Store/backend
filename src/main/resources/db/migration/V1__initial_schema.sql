-- Repos: source of truth for all indexed GitHub repositories
CREATE TABLE repos (
    id                        BIGINT PRIMARY KEY,
    full_name                 TEXT UNIQUE NOT NULL,
    owner                     TEXT NOT NULL,
    name                      TEXT NOT NULL,
    owner_avatar_url          TEXT,
    description               TEXT,
    default_branch            TEXT,
    html_url                  TEXT NOT NULL,
    stars                     INT NOT NULL DEFAULT 0,
    forks                     INT NOT NULL DEFAULT 0,
    language                  TEXT,
    topics                    TEXT[] NOT NULL DEFAULT '{}',
    latest_release_date       TIMESTAMPTZ,
    latest_release_tag        TEXT,

    has_installers_android    BOOLEAN NOT NULL DEFAULT FALSE,
    has_installers_windows    BOOLEAN NOT NULL DEFAULT FALSE,
    has_installers_macos      BOOLEAN NOT NULL DEFAULT FALSE,
    has_installers_linux      BOOLEAN NOT NULL DEFAULT FALSE,

    install_count             INT NOT NULL DEFAULT 0,
    install_success_rate      REAL,
    view_count_7d             INT NOT NULL DEFAULT 0,

    trending_score            REAL,
    popularity_score          REAL,

    created_at_gh             TIMESTAMPTZ,
    updated_at_gh             TIMESTAMPTZ,
    indexed_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    tsv_search                TSVECTOR
);

CREATE INDEX idx_repos_tsv_search ON repos USING GIN (tsv_search);
CREATE INDEX idx_repos_topics ON repos USING GIN (topics);
CREATE INDEX idx_repos_stars ON repos (stars DESC);
CREATE INDEX idx_repos_latest_release ON repos (latest_release_date DESC NULLS LAST);

-- Auto-update tsv_search on insert/update
CREATE OR REPLACE FUNCTION repos_tsv_trigger() RETURNS trigger AS $$
BEGIN
    NEW.tsv_search :=
        setweight(to_tsvector('english', COALESCE(NEW.full_name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(array_to_string(NEW.topics, ' '), '')), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_repos_tsv
    BEFORE INSERT OR UPDATE ON repos
    FOR EACH ROW EXECUTE FUNCTION repos_tsv_trigger();

-- Category membership (trending, new-releases, most-popular per platform)
CREATE TABLE repo_categories (
    repo_id    BIGINT REFERENCES repos(id) ON DELETE CASCADE,
    category   TEXT NOT NULL,
    platform   TEXT NOT NULL,
    rank       INT NOT NULL,
    PRIMARY KEY (repo_id, category, platform)
);

CREATE INDEX idx_repo_categories_lookup ON repo_categories (category, platform, rank);

-- Topic buckets (privacy, media, productivity, networking, dev-tools)
CREATE TABLE repo_topic_buckets (
    repo_id    BIGINT REFERENCES repos(id) ON DELETE CASCADE,
    bucket     TEXT NOT NULL,
    platform   TEXT NOT NULL,
    rank       INT NOT NULL,
    PRIMARY KEY (repo_id, bucket, platform)
);

CREATE INDEX idx_repo_topic_buckets_lookup ON repo_topic_buckets (bucket, platform, rank);

-- Telemetry events (opt-in, anonymous)
CREATE TABLE events (
    id              BIGSERIAL PRIMARY KEY,
    ts              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    device_id       TEXT NOT NULL,
    platform        TEXT NOT NULL,
    app_version     TEXT,
    event_type      TEXT NOT NULL,
    repo_id         BIGINT,
    query_hash      TEXT,
    result_count    INT,
    success         BOOLEAN,
    error_code      TEXT
);

CREATE INDEX idx_events_ts ON events (ts);
CREATE INDEX idx_events_type_ts ON events (event_type, ts);
CREATE INDEX idx_events_repo ON events (repo_id);

-- Rolled-up daily stats per repo
CREATE TABLE repo_stats_daily (
    repo_id          BIGINT REFERENCES repos(id) ON DELETE CASCADE,
    date             DATE NOT NULL,
    views            INT,
    searches_hit     INT,
    installs_started INT,
    installs_success INT,
    PRIMARY KEY (repo_id, date)
);

-- Search miss queue: queries that returned 0 results
CREATE TABLE search_misses (
    query_hash   TEXT PRIMARY KEY,
    query_sample TEXT,
    miss_count   INT NOT NULL DEFAULT 1,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
