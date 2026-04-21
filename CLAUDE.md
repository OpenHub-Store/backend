# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product context

Backend for **GitHub Store** (a KMP + Compose Multiplatform app store — Android + Desktop, ~90k users). The client app lives at `/Users/rainxchzed/Documents/development/kmp/GitHub-Store`. The Python fetcher that feeds data into this backend lives at `/Users/rainxchzed/Documents/development/backend/api`.

**The backend is optional by design.** The client falls back to cached JSON files in `OpenHub-Store/api` on GitHub when the backend is unreachable. Never design features that require the backend to be up.

**Live deployment:** single Hetzner VPS (4 vCPU / 8 GB) at `<VPS_IP>`, served through two hostnames:
- `https://api.github-store.org/v1/` — CDN-fronted (Gcore, free plan). Normal path.
- `https://api-direct.github-store.org/v1/` — direct-to-origin, bypasses the CDN. Used as client-side fallback when the CDN path is throttled (mainly Chinese ISPs flagging shared CDN IPs) and as origin-pull target for Gcore itself.

Both hostnames serve identical responses. Caddy on the VPS terminates TLS (Let's Encrypt) for both. See `GITHUB_STORE_BACKEND_PLAN.md` for the full strategic doc and `CLIENT_MIGRATION_X_GITHUB_TOKEN.md` for the client contract.

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
| `GET /search?q=&platform=&sort=&limit=&offset=` | Meilisearch-powered search. Auto-triggers GitHub passthrough if <5 results. Reads optional `X-GitHub-Token` header to run passthrough on the user's 5000/hr quota instead of the backend's fallback quota. Response carries `passthroughAttempted: Boolean` so clients can distinguish "index was warm but returned nothing" from "GitHub also has nothing". |
| `GET /search/explore?q=&platform=&page=` | User-triggered deep GitHub search, paginated, ingests into index. Also reads `X-GitHub-Token`. Cold-path latency is 10–30s — clients must use a 30s timeout. |
| `GET /categories/{trending\|new-releases\|most-popular}/{android\|windows\|macos\|linux}` | Pre-ranked repo lists. Sort order is `search_score DESC NULLS LAST, rank ASC` — static `rank` is only the tie-breaker once behavioral signals exist. |
| `GET /topics/{privacy\|media\|productivity\|networking\|dev-tools}/{platform}` | Topic-bucketed repos. Same dynamic ordering as categories. |
| `GET /repo/{owner}/{name}` | Single repo detail |
| `POST /events` | Batched telemetry (opt-in, max 50 per batch). These rows drive `SignalAggregationWorker` — ranking only improves if clients send events. |
| `POST /auth/device/start` | Stateless proxy for `github.com/login/device/code`. Client used to call GitHub directly; some user networks (documented in OpenHub-Store/GitHub-Store#433, #395) can't reach GitHub reliably. Backend adds `client_id`, forwards GitHub's body verbatim. 10 req/hr/IP. |
| `POST /auth/device/poll` | Stateless proxy for `github.com/login/oauth/access_token`. Reads `device_code` from form body, adds `client_id` + `grant_type`, forwards GitHub's body verbatim (including tokens on success). The backend never logs, caches, or persists the token. 200 req/hr/IP. |
| `GET /internal/metrics` | Operator-only. Gated by `X-Admin-Token` matching the `ADMIN_TOKEN` env var (open if unset, for local dev). Returns per-source search counters, P-latency, worker queue depth, and top 20 misses in last 7 days. |

Client-facing API contract is documented in `API_CLIENT_GUIDE.md`. Keep that file in sync when changing response shapes. The client-side migration doc (token forwarding, fallback host, timeouts, `passthroughAttempted` interpretation) is `CLIENT_MIGRATION_X_GITHUB_TOKEN.md`.

## Architecture

**Data flow:**

```
Python fetcher (daily cron on VPS: /opt/fetcher/)
    │ upserts via db_writer.py
    ▼
Postgres (source of truth) ◀── SignalAggregationWorker (hourly)
    │                              ▲
    │ synced by meili_sync.py      │ reads Events, writes RepoSignals + search_score
    ▼                              │
Meilisearch (search index) ◀───────┘ (pushes search_score via PUT /documents)
    ▲
    │ on-demand ingest writes to both (async, off the request path)
Ktor app → GitHubSearchClient → GitHub API
    │
    │ periodic warming
    ▼
SearchMissWorker (hourly)   — picks top misses, runs explore() against GitHub
RepoRefreshWorker (hourly)  — re-fetches passthrough repos by oldest indexed_at
```

**Key architectural decisions:**

- **No Flyway.** Flyway 11 community edition rejected standard `V1__` naming. Migrations run via `DatabaseFactory.runMigrations()` — reads SQL from `src/main/resources/db/migration/` and executes directly via Exposed. V1 is idempotent (table-exists check), V2+ are additive and wrapped in try/catch. New migrations must be added to the `migrations` list in `DatabaseFactory.kt`.
- **Koin DI but no `Route.inject()`.** Koin's `Route.inject()` extension breaks in Ktor 3.x (references a moved class). Dependencies are injected at the `Application` level in `routes/Routing.kt` and passed as parameters to route functions. The three workers (`SearchMissWorker`, `SignalAggregationWorker`, `RepoRefreshWorker`) are `single { ... }` in `AppModule.kt` and started from `Application.module()`.
- **Rate limiting** keys off `CF-Connecting-IP` first, then `X-Forwarded-For`, then socket IP (see `Plugins.kt`). The CF header is leftover from the pre-Gcore era — in production now the `X-Forwarded-For` branch handles real traffic. Keep both so a future CDN swap doesn't silently collapse all users onto one bucket.
- **Two search paths and a third training path:**
  1. **Automatic passthrough**: when Meilisearch returns <5 results, `GitHubSearchClient.searchAndIngest()` runs in the same request. Merges results before responding. Near-misses (1–4 results) are logged to `SearchMisses` with `result_count` so the worker can prioritize zero-result > sparse.
  2. **Explicit explore**: `/search/explore` is a user-triggered paginated fetch. Each page adds up to 10 new repos to the index.
  3. **Background warming**: `SearchMissWorker` re-runs `explore()` on the most-missed queries once an hour; they become re-processable after 7 days.
- **Persistence is off the request path.** `GitHubSearchClient` owns a `persistenceScope` (`SupervisorJob + Dispatchers.IO`). The live search returns the enriched results synchronously; the Postgres upsert + Meilisearch push are fire-and-forget coroutines so the user never waits on them. A failure in persistence is logged, not surfaced.
- **GitHub token strategy: bring-your-own + rotation pool fallback.** `/search` and `/search/explore` read an optional `X-GitHub-Token` header from the client and use that token for the passthrough (scales quota linearly with users). When the client sends none, `GitHubSearchClient.pickFallbackToken()` round-robins across a pool of four backend PATs (`GH_TOKEN_TRENDING`, `GH_TOKEN_NEW_RELEASES`, `GH_TOKEN_MOST_POPULAR`, `GH_TOKEN_TOPICS`). During the fetcher's quiet window (UTC hours `TOKEN_QUIET_START_UTC`–`TOKEN_QUIET_END_UTC`, default 1–4) the pool is bypassed and only `GITHUB_TOKEN` is used so the Python fetcher gets the whole pool to itself. Any change here must preserve the quiet-window guarantee.
- **Auth is a stateless proxy, not a session.** `/v1/auth/device/*` forwards to `github.com/login/*` with the backend's `GITHUB_OAUTH_CLIENT_ID` injected. The backend must **never** log, cache, or persist the access token returned by a successful poll — it passes through the suspending handler and out to the HTTP response, nothing else. No database table, no in-memory map, no breadcrumb. The client is the only place the token lives. Client is backend-first on these two calls and falls back to direct-to-github.com on 5xx / network errors (only — not on valid-but-negative responses like `authorization_pending` or `access_denied`, which are GitHub's real answer and `github.com` direct would say the same thing). Full design: `~/.claude/plans/backend-first-github-auth-proxy.md` and `CLIENT_AUTH_PROXY_MIGRATION.md`.
- **Unified ranking via `SearchScore.compute()`** (`ranking/SearchScore.kt`). Formula: `0.40·log₁₀(stars+1)/6 + 0.30·ctr + 0.20·install_success_rate + 0.10·exp(-days_since_release/90)`. Two callers: `SignalAggregationWorker` (hourly, with real signals) and `GitHubSearchClient` at ingest time (cold-start, signals = 0 — still gives passthrough repos a non-null score so they sort). Weights live in the object only; never inline the formula elsewhere.
- **Meilisearch partial-update gotcha — PUT, never POST.** `MeilisearchClient.addDocuments()` is POST, which on Meili *replaces* the document with whatever fields you send (everything else becomes null). `MeilisearchClient.updateScores()` is PUT, which merges. Pushing just `{id, search_score}` with POST will wipe every other field on 3000+ docs. If you add a new "partial update" path, verify the HTTP verb before deploying.
- **Dynamic category/topic ordering.** `RepoRepository.findByCategory()` / `findByTopicBucket()` sort by `searchScore DESC NULLS LAST, rank ASC`. The Python fetcher's static `rank` is only a tie-breaker now; behavioral signals dominate.
- **Exposed `Repos` table uses `array<String>("topics", TextColumnType())`** for the Postgres `TEXT[]` column. The Python fetcher writes these via psycopg2's automatic list-to-array conversion.
- **Cache headers are set per endpoint**, not globally. Categories/topics: 60s/120s. Repo detail: 30s/60s. Search: 15s/30s. Gcore (and any other edge) respects `s-maxage` for edge caching. `/internal/metrics` is uncached.

## Conventions

- All API routes are under `/v1/` and additive only. Never break client apps on field changes.
- One file per route resource in `routes/`. Routes receive repositories/clients as parameters, not via `inject()`.
- Migrations are numbered: `V1__`, `V2__`, etc. Each new migration needs to be added to the `migrations` list in `DatabaseFactory.kt`.
- Never log raw search queries, device IDs, or `X-GitHub-Token` values. Hash queries/devices via `SearchMissRepository.sha256()`; treat tokens as secrets — no logging, no Sentry breadcrumbs, no metrics labels.
- Use `rainxch.githubstore.ranking.SearchScore.compute(...)` for any ranking score anywhere. The formula is centralized on purpose — never re-derive weights inline.
- Commit style: **one logical change per commit.** Batched commits are discouraged (stored as user preference). Code + tests + docs for the same change go together, but separate changes stay separate.
- When adding a new field to `RepoResponse`: update the Exposed `Tables.kt`, the `RepoRepository.toRepoResponse()` mapper, the `MeiliRepoHit` DTO, the `SearchRoutes` mapper, the on-demand ingest in `GitHubSearchClient`, AND the Python `meili_sync.py` query — all five need to agree or data is silently dropped.

## Deployment notes

- `.env` lives only on the VPS at `/opt/github-store-backend/.env`, never in git. Required keys: `POSTGRES_PASSWORD`, `MEILI_MASTER_KEY`, `GITHUB_TOKEN` (quiet-window fallback), `GH_TOKEN_TRENDING` / `GH_TOKEN_NEW_RELEASES` / `GH_TOKEN_MOST_POPULAR` / `GH_TOKEN_TOPICS` (rotation pool), `GITHUB_OAUTH_CLIENT_ID` (for `/v1/auth/device/*` — same OAuth App client_id the KMP client has in BuildKonfig; backend refuses to start without it), optional `ADMIN_TOKEN` (gates `/v1/internal/metrics`), optional `SENTRY_DSN`, optional `TOKEN_QUIET_START_UTC` / `TOKEN_QUIET_END_UTC` (default 1 / 4).
- `docker-compose.override.yml` is local-dev only (exposes DB/search ports to host). Never deploy it — `.dockerignore` excludes it.
- Production uses `docker-compose.prod.yml` which does NOT expose Postgres/Meilisearch ports (only Caddy on 80/443). Postgres binds `127.0.0.1:5432` for the SSH tunnel from laptop.
- `Caddyfile.prod` defines two site blocks: `api.github-store.org` (CDN-fronted primary) and `api-direct.github-store.org` (direct origin fallback). Both reverse-proxy to `app:8080` and both issue Let's Encrypt certs. Gcore's origin pull protocol must be **HTTPS**, not HTTP — HTTP triggers Caddy's auto-redirect and leaks the direct hostname.
- `deploy.sh` runs `caddy reload` after sync and falls back to `docker compose restart caddy` if reload fails. The Caddyfile is bind-mounted, so file changes alone don't reconfigure the running process.
- Python fetcher runs on the VPS at `/opt/fetcher/` via cron: full fetch daily at 02:00 UTC, new-releases every 3h. The fetcher consumes the same 4-token pool as the backend's rotation pool — the backend's `TOKEN_QUIET_START_UTC`/`END_UTC` window exists precisely to keep the pool free for it during its run.
- Sentry DSN is pulled from `SENTRY_DSN` env var at startup; init is a no-op if unset (good for local dev).
