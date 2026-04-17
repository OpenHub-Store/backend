# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product context

Backend for **GitHub Store** (a KMP + Compose Multiplatform app store — Android + Desktop, ~90k users). The client app lives at `/Users/rainxchzed/Documents/development/kmp/GitHub-Store`. The Python fetcher that feeds data into this backend lives at `/Users/rainxchzed/Documents/development/backend/api`.

**The backend is optional by design.** The client falls back to cached JSON files in `OpenHub-Store/api` on GitHub when the backend is unreachable. Never design features that require the backend to be up.

**Live deployment:** `https://api.github-store.org/v1/` — single Hetzner VPS (4 vCPU / 8 GB) at `<VPS_IP>` behind Cloudflare. See `GITHUB_STORE_BACKEND_PLAN.md` for the full strategic doc.

## Common commands

```bash
# Local development (exposes Postgres/Meilisearch ports to host via docker-compose.override.yml)
docker compose up postgres meilisearch -d
./gradlew buildFatJar
DATABASE_URL="jdbc:postgresql://localhost:5432/githubstore" \
  DATABASE_PASSWORD="githubstore" \
  MEILI_URL="http://localhost:7700" \
  MEILI_MASTER_KEY="devkey" \
  java -jar build/libs/github-store-backend.jar

# Full stack via Docker (dev compose, uses override file for exposed ports)
docker compose up -d --build

# Compile only (fast feedback loop)
./gradlew compileKotlin

# Deploy to production VPS (rsync + docker compose)
./deploy.sh <VPS_IP>

# Check production
curl https://api.github-store.org/v1/health
```

## API surface

All under `/v1/`:

| Endpoint | Purpose |
|----------|---------|
| `GET /health` | Health check (Postgres + Meilisearch status) |
| `GET /search?q=&platform=&sort=&limit=&offset=` | Meilisearch-powered search, auto-triggers GitHub passthrough if <5 results |
| `GET /search/explore?q=&platform=&page=` | User-triggered deep GitHub search, paginated, ingests into index |
| `GET /categories/{trending\|new-releases\|most-popular}/{android\|windows\|macos\|linux}` | Pre-ranked repo lists |
| `GET /topics/{privacy\|media\|productivity\|networking\|dev-tools}/{platform}` | Topic-bucketed repos |
| `GET /repo/{owner}/{name}` | Single repo detail |
| `POST /events` | Batched telemetry (opt-in, max 50 per batch) |

Client-facing API contract is documented in `API_CLIENT_GUIDE.md`. Keep that file in sync when changing response shapes.

## Architecture

**Data flow:**

```
Python fetcher (daily cron on VPS: /opt/fetcher/)
    │ upserts via db_writer.py
    ▼
Postgres (source of truth)
    │ synced by meili_sync.py
    ▼
Meilisearch (search index)
    ▲
    │ on-demand ingest writes to both
Ktor app → GitHubSearchClient → GitHub API
```

**Key architectural decisions:**

- **No Flyway.** Flyway 11 community edition rejected standard `V1__` naming. Migrations run via `DatabaseFactory.runMigrations()` — reads SQL from `src/main/resources/db/migration/` and executes directly via Exposed. V1 is idempotent (table-exists check), V2+ are additive and wrapped in try/catch.
- **Koin DI but no `Route.inject()`.** Koin's `Route.inject()` extension breaks in Ktor 3.x (references a moved class). Dependencies are injected at the `Application` level in `routes/Routing.kt` and passed as parameters to route functions.
- **Rate limiting uses `CF-Connecting-IP`.** Because we're always behind Cloudflare in production, rate limits key off the real client IP from that header, not the socket IP (which is always Cloudflare).
- **Two search paths:**
  1. **Automatic passthrough**: when Meilisearch returns <5 results, `GitHubSearchClient.searchAndIngest()` runs in the same request. Merges results before responding.
  2. **Explicit explore**: `/search/explore` is a user-triggered paginated fetch. Each page adds 10 new repos to the index.
- **Exposed `Repos` table uses `array<String>("topics", TextColumnType())`** for the Postgres `TEXT[]` column. The Python fetcher writes these via psycopg2's automatic list-to-array conversion.
- **Cache headers are set per endpoint**, not globally. Categories/topics: 60s/120s. Repo detail: 30s/60s. Search: 15s/30s. Cloudflare respects `s-maxage` for edge caching.

## Conventions

- All API routes are under `/v1/` and additive only. Never break client apps on field changes.
- One file per route resource in `routes/`. Routes receive repositories/clients as parameters, not via `inject()`.
- Migrations are numbered: `V1__`, `V2__`, etc. Each new migration needs to be added to the `migrations` list in `DatabaseFactory.kt`.
- Never log raw search queries or device IDs. Hash them (see `SearchMissRepository.sha256()`).
- When adding a new field to `RepoResponse`: update the Exposed `Tables.kt`, the `RepoRepository.toRepoResponse()` mapper, the `MeiliRepoHit` DTO, the `SearchRoutes` mapper, the on-demand ingest in `GitHubSearchClient`, AND the Python `meili_sync.py` query — all four need to agree or data is silently dropped.

## Deployment notes

- `.env` lives only on the VPS at `/opt/github-store-backend/.env`, never in git.
- `docker-compose.override.yml` is local-dev only (exposes DB/search ports to host). Never deploy it — `.dockerignore` excludes it.
- Production uses `docker-compose.prod.yml` which does NOT expose Postgres/Meilisearch ports (only Caddy on 80/443).
- Python fetcher runs on the VPS at `/opt/fetcher/` via cron: full fetch daily at 02:00 UTC, new-releases every 3h.
- Sentry DSN is pulled from `SENTRY_DSN` env var at startup; init is a no-op if unset (good for local dev).
