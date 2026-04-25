# GitHub Store — Backend

A small, opinionated Kotlin/Ktor backend that powers an app store of FOSS GitHub apps for Android and Desktop. It serves around **90,000 users** off a single Hetzner VPS, talks to Postgres + Meilisearch, and is built on one stubborn principle:

> **The backend is allowed to be down.**

The client app (KMP + Compose Multiplatform) keeps a copy of the world as static JSON files committed to a public GitHub repo, and falls back to those whenever this service is unreachable. So everything below — the search, the ranking, the device-flow auth proxy, the M3 badges — is the *fast path*. The slow path is "fetch a JSON file from raw.githubusercontent.com." That framing changes a lot of decisions.

This README walks the architecture top to bottom: the data flow, the search machinery, the ranking formula, the bring-your-own-token mechanism, the amnesiac auth proxy, and the badge service whose output you can already see at the top of this page.

<p>
  <img src="https://api.github-store.org/v1/badge/static/8/2?label=Kotlin&icon=code" alt="Kotlin"/>
  <img src="https://api.github-store.org/v1/badge/static/12/2?label=Ktor%203.1.2&icon=bolt" alt="Ktor 3.1.2"/>
  <img src="https://api.github-store.org/v1/badge/static/5/2?label=Postgres&icon=package" alt="Postgres"/>
  <img src="https://api.github-store.org/v1/badge/static/3/2?label=Meilisearch&icon=star" alt="Meilisearch"/>
  <img src="https://api.github-store.org/v1/badge/static/11/2?label=Material%20You&icon=palette" alt="Material You"/>
</p>

---

## 📚 Table of contents

1. [The hook](#the-hook)
2. [Optional by design](#optional-by-design)
3. [Architecture](#architecture)
4. [API at a glance](#api-at-a-glance)
5. [How search actually works](#how-search-actually-works)
6. [How ranking works](#how-ranking-works)
7. [Bring-your-own GitHub token](#bring-your-own-github-token)
8. [The auth proxy that doesn't remember anything](#the-auth-proxy-that-doesnt-remember-anything)
9. [The badge service](#the-badge-service)
10. [Tech stack and project layout](#tech-stack-and-project-layout)
11. [Local development](#local-development)
12. [Deployment](#deployment)
13. [Operational notes and war stories](#operational-notes-and-war-stories)
14. [Attribution](#attribution)

---

## The hook

The interesting facts, before we get formal:

- **One VPS, two hostnames.** `api.github-store.org` is fronted by Gcore's free CDN. `api-direct.github-store.org` skips the CDN entirely and points straight at Hetzner. The client tries the CDN first and falls through to the direct host on timeout — useful for users on networks (some Chinese ISPs, mostly) where shared CDN IPs get flagged.
- **The search has three brains.** A warm Meilisearch index. A synchronous fallback that asks GitHub when the index comes up short. And a background worker that watches what people *failed* to find and quietly trains the index overnight.
- **Users pay for their own searches.** Clients forward the user's GitHub OAuth token in `X-GitHub-Token`. The backend uses it for the live passthrough, then forgets it. Each authenticated user contributes their own 5,000/hr GitHub quota; the backend pool is just for anonymous users.
- **The auth endpoint is amnesiac.** `/v1/auth/device/poll` proxies GitHub's device-code flow. It never logs, caches, or persists the access token, not even in memory. The token is born, walks through one suspending handler, and goes home to the client.
- **The READMEs you see in the app are *also* served from here.** Including the Material You-styled badges in this very README. The backend renders SVG badges with a 12-hue × 3-shade palette ported one-to-one from [`ziadOUA/m3-Markdown-Badges`](https://github.com/ziadOUA/m3-Markdown-Badges), so anyone can use `<img src="https://api.github-store.org/v1/badge/...">` in their own project.

The code that does all of this is one Gradle module, ~50 Kotlin files. Read on.

---

## Optional by design

This is the load-bearing decision. Everything else falls out of it.

The KMP client app (`OpenHub-Store/GitHub-Store`) ships with a list of endpoints:

```
1. https://api.github-store.org/v1/...        (CDN)
2. https://api-direct.github-store.org/v1/... (direct origin)
3. https://raw.githubusercontent.com/OpenHub-Store/api/main/*.json  (static fallback)
```

The static fallback isn't a placeholder. The Python fetcher (next section) writes its full output as a tree of JSON files into a sibling repo, `OpenHub-Store/api`. Categories, topics, repo details — all there, refreshed daily, served by GitHub's CDN, free, and effectively un-DDOS-able.

So when you're reading the API surface below, mentally tag every endpoint with "what's the JSON-file equivalent of this?". Every category route maps to a file. Every repo detail maps to a file. The endpoints that *don't* have a static equivalent — `/search`, `/search/explore`, `/auth/device/*` — are the ones where the backend is genuinely contributing something the static path can't do.

What this buys:

- **Outages stop being P0.** Health check fails at 3am? The app keeps working. Calmly fix it in the morning.
- **A green light to experiment.** Index migrations, ranking tweaks, schema changes, all of it can ship with confidence because the worst case is "users get yesterday's data."
- **Less infrastructure.** No multi-region. No standby. No paging. One VPS, one Postgres, one Meilisearch, one Caddy.

What it costs: the search experience is degraded when the backend's down (no live passthrough, no Meilisearch typo correction). That's a fair trade.

---

## Architecture

```
                           ┌─────────────────────────────┐
                           │  Python fetcher (cron)      │
                           │  /opt/fetcher/  on the VPS  │
                           │  • full fetch  02:00 UTC    │
                           │  • new releases  every 3h   │
                           └──────────────┬──────────────┘
                                          │ db_writer.py
                                          ▼
   ┌─────────────────────────┐    ┌──────────────────────┐
   │  SignalAggregationWorker│◀───│      PostgreSQL      │──┐
   │  (hourly)               │    │  source of truth     │  │ meili_sync.py
   │  reads Events           │    └──────────────────────┘  │ (push)
   │  writes RepoSignals     │              ▲               │
   │  + search_score         │              │ upserts        │
   └────────────┬────────────┘              │               ▼
                │ PUT /documents (merge)    │       ┌──────────────────┐
                ▼                           │       │   Meilisearch    │
        ┌───────────────────┐               │       │   (search index) │
        │   Meilisearch     │◀──────────────┴───────│                  │
        └───────────────────┘                       └──────────────────┘
                ▲                                            ▲
                │                                            │
                │       ┌─────────────────────────┐          │
                │       │   GitHubSearchClient    │──────────┘ on-demand
                └───────│  • passthrough          │             ingest
                        │  • explore              │             (async)
                        │  • token rotation pool  │
                        └────────────┬────────────┘
                                     │
                ┌────────────────────┴───────────────────┐
                │              Ktor app                  │
                │  routes/  •  workers  •  badge service │
                └────────┬───────────────────────────────┘
                         │
                    Caddy (TLS)
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
      api.github-store.org   api-direct.github-store.org
              │                     │
              ▼                     │
         Gcore CDN ────origin pull──┘
              │
              ▼
        KMP client app
              │
              └── on failure ──▶ raw.githubusercontent.com/OpenHub-Store/api
```

Three independent loops keep the index honest:

| Worker | Cadence | Job |
|---|---|---|
| `SignalAggregationWorker` | hourly | Reads `Events`, computes CTR + install success, recomputes `search_score`, pushes the score field to Meilisearch |
| `SearchMissWorker` | hourly | Picks the top recent zero/low-result queries from `SearchMisses`, runs `explore()` against GitHub, ingests new repos. Re-eligible after 7 days. |
| `RepoRefreshWorker` | hourly | Re-fetches passthrough-discovered repos, oldest `indexed_at` first, so star counts and release dates don't go stale |

All three are plain coroutines launched from `Application.module()`. No Quartz, no Temporal, nothing fancy. They run on a `SupervisorJob` so one blow-up doesn't take the others down.

---

## API at a glance

Everything is under `/v1/`. Field shapes are documented inline on each handler in `routes/` — the response data classes in `model/` are the contract.

| Endpoint | Purpose |
|---|---|
| `GET /health` | Postgres + Meilisearch health |
| `GET /search?q=&platform=&sort=&limit=&offset=` | Meilisearch + auto GitHub passthrough when fewer than 5 results. Reads optional `X-GitHub-Token`. Response carries `passthroughAttempted: Boolean`. |
| `GET /search/explore?q=&platform=&page=` | User-triggered deep paginated search. Each page ingests up to 10 new repos. Cold latency 10–30s — clients use a 30s timeout. |
| `GET /categories/{trending\|new-releases\|most-popular}/{android\|windows\|macos\|linux}` | Pre-ranked lists, sorted `search_score DESC NULLS LAST, rank ASC` |
| `GET /topics/{privacy\|media\|productivity\|networking\|dev-tools}/{platform}` | Topic-bucketed repos, same sort |
| `GET /repo/{owner}/{name}` | Single repo detail |
| `GET /readme/{owner}/{name}` | Opaque proxy of GitHub `/repos/{}/{}/readme`, cached |
| `GET /user/{username}` | Opaque proxy of GitHub `/users/{}`, cached |
| `POST /events` | Batched telemetry, max 50 per batch. Drives the ranking signals. |
| `POST /auth/device/start` | Stateless proxy to GitHub device-code start. 10/hr/IP. |
| `POST /auth/device/poll` | Stateless proxy to GitHub device-code poll. 200/hr/IP. |
| `GET /badge/...` | M3-styled SVG badges (see [The badge service](#the-badge-service)) |
| `GET /internal/metrics` | Operator-only, gated by `X-Admin-Token` |

Cache headers are set per endpoint, not globally. Rough budgets:

| Surface | `max-age` | `s-maxage` |
|---|---|---|
| Categories / topics | 60s | 120s |
| Repo detail | 30s | 60s |
| Search | 15s | 30s |
| Badges (fresh) | 3600s | 3600s + `stale-while-revalidate=86400` |
| `/internal/metrics` | uncached | uncached |

Routes use plain function-per-resource files in `routes/`. Dependencies (repositories, clients) get injected at the `Application` level and passed in as parameters — see [the no-`Route.inject()` story](#operational-notes-and-war-stories).

---

## How search actually works

There are three search paths. They look like one to the client.

### 1. The warm path — Meilisearch

The Python fetcher loads ~3,000 curated repos into Postgres daily. `meili_sync.py` mirrors them into Meilisearch with the searchable fields (`name`, `full_name`, `description`, `topics`) and the ranking score. A `/search` call hits Meilisearch first. If it returns five or more hits, that's your answer. Median response: tens of milliseconds.

### 2. The synchronous fallback — passthrough

If Meilisearch returns fewer than 5 hits, the same request runs `GitHubSearchClient.searchAndIngest()` synchronously. It hits GitHub's `/search/repositories` endpoint, filters for "looks like an app" (has releases, has a desktop/mobile binary in its assets, etc.), merges with whatever Meilisearch did find, and responds. Persistence — writing the new repos to Postgres and pushing them to Meilisearch — runs as a fire-and-forget coroutine on a `SupervisorJob + Dispatchers.IO` scope. The user does not wait for the writes.

The response carries `passthroughAttempted: Boolean` so the client can tell apart:

- "Meilisearch had results, no passthrough needed" — `passthroughAttempted: false`
- "Meilisearch came up short, we asked GitHub, GitHub also has nothing" — `passthroughAttempted: true, results: []`

That second case is the one a user sees as "this app doesn't exist," and it's worth distinguishing from a stale-index miss.

### 3. The training path — explore + miss worker

Two flavors:

- **Explicit** — `/search/explore` is a separate endpoint a user triggers from the UI ("look harder"). It's paginated, cold-path, ingests up to 10 new repos per page. Latency is 10–30s. Clients must use a 30s timeout.
- **Background** — when a `/search` query produces zero or sparse hits, it gets logged to `SearchMisses` (with a SHA-256 of the query string, never the query itself). `SearchMissWorker` runs hourly, picks the top recent misses (zero-result first, then 1–4 result misses), runs `explore()` against GitHub, and adds whatever it finds. A query becomes re-processable after 7 days, so we don't loop on terms that GitHub really has nothing for.

The point: the index gets *better* the more people search for things it doesn't have. Cold-start handled, kind of for free.

---

## How ranking works

There is one function that computes a search score, and it lives in [`ranking/SearchScore.kt`](src/main/kotlin/zed/rainxch/githubstore/ranking/SearchScore.kt):

```kotlin
score = 0.40 · log₁₀(stars + 1) / 6
      + 0.30 · ctr
      + 0.20 · install_success_rate
      + 0.10 · exp(-days_since_release / 90)
```

Each factor is clamped to `[0, 1]`. Weights sum to `1.0`. Two callers, one formula, no other ranking logic anywhere in the codebase.

What each weight is doing:

| Weight | Factor | Why |
|---|---|---|
| **0.40** | `log₁₀(stars+1) / 6` | Stars are the only universal signal we have on day one. The log compresses the long tail (a 100k-star repo is not 1000× better than a 100-star repo); the `/6` normalizes ~1M stars to 1.0. |
| **0.30** | `ctr` (click-through) | When users see the repo in a list and tap into it, that's the cleanest "this is what I wanted" signal we get. |
| **0.20** | `install_success_rate` | If the click resolves into a successful install, double-bonus. This punishes apps that look interesting but ship broken APKs. |
| **0.10** | `exp(-days_since_release / 90)` | Light recency bias. A repo that shipped a release this week shows up a bit higher than one that went quiet a year ago. Half-life is ~62 days. |

Two callers:

- **`SignalAggregationWorker`** — runs hourly, fills in real `ctr` and `install_success_rate` from the `Events` table, recomputes for every repo, pushes to Meilisearch via `MeilisearchClient.updateScores()` (PUT, not POST — see war stories).
- **`GitHubSearchClient`** at ingest — when a passthrough or explore call discovers a new repo, we still need to score it. We call `SearchScore.compute(stars = N)` with `ctr = 0`, `installSuccessRate = 0`, `daysSinceRelease = ...`. The repo gets a non-null score immediately, sorts naturally alongside everything else, and the next hourly aggregation backfills behavioral signals once events come in.

Cold-start, in plain English: a new repo's score is `0.40 · stars + 0.10 · recency`, max 0.50. An established repo with great signals can hit ~1.0. So newcomers can rank, but the ceiling rewards repos that have actually earned it.

The Python fetcher has its own static `rank` column for the curated set. In the response sort it's only the tie-breaker now: `ORDER BY search_score DESC NULLS LAST, rank ASC`. The behavioral score dominates as soon as it exists.

---

## Bring-your-own GitHub token

GitHub's REST API is rate-limited per-token. Anonymous: 60/hr. Authenticated: 5,000/hr. The interesting move is letting users bring their own quota.

```
Client → backend                          backend → github.com
    │                                          │
    │   GET /v1/search?q=signal                │
    │   X-GitHub-Token: ghu_AAAA…    ─┐        │
    │                                 ├──────▶ │   Authorization:
    │                                 │        │   token ghu_AAAA…
    │                                 │        │
    │                            (used once,
    │                             never stored)
```

If `X-GitHub-Token` is present, that's the token the backend uses for the passthrough. It never gets logged, hashed-into-metrics, or persisted. It lives inside the suspending handler frame and that's it.

If `X-GitHub-Token` is absent, `GitHubSearchClient.pickFallbackToken()` round-robins across a pool of four backend PATs:

```
GH_TOKEN_TRENDING        GH_TOKEN_NEW_RELEASES
GH_TOKEN_MOST_POPULAR    GH_TOKEN_TOPICS
```

Why a pool? Because we share it. The Python fetcher uses the same four tokens for its daily run. To keep them out of each other's way, the backend has a quiet-window bypass:

```
TOKEN_QUIET_START_UTC=1   # default 01:00 UTC
TOKEN_QUIET_END_UTC=4     # default 04:00 UTC
```

During the quiet window, the rotation pool is short-circuited and the backend uses the single `GITHUB_TOKEN` env var for any anonymous passthrough. The pool stays clean for the fetcher. The fetcher runs at 02:00 UTC and finishes before 04:00 with room to spare.

The math: at 90k MAU, even a 1% authenticated rate gives ~900 users × 5,000/hr = 4.5M/hr of headroom against GitHub's quotas. The backend has never had to throttle for token shortage.

---

## The auth proxy that doesn't remember anything

Some users (issues #433 and #395 on the public client repo) couldn't reach `github.com` directly to start the device-flow login. Their ISPs or firewalls would happily reach `api.github-store.org`, but block GitHub. So the backend now proxies the device flow:

```
POST /v1/auth/device/start   →   POST github.com/login/device/code
POST /v1/auth/device/poll    →   POST github.com/login/oauth/access_token
```

Two rules carved into stone, before anything else:

1. **No `client_secret`.** This is a public OAuth app (device flow doesn't use a secret). The backend injects `client_id` and `grant_type` from env, forwards the rest, returns GitHub's body verbatim — including the `access_token` on success.
2. **Zero retention.** The successful poll's response body — the one with the user's GitHub token in it — passes through one suspending handler and goes out as the HTTP response. It is never logged, never stored in a Postgres table, never put in an in-memory cache, never tagged onto Sentry, never used as a metrics label. There is no breadcrumb.

The client is the *only* place a user's GitHub token lives. The backend is just a postal forwarding service that, by policy, is forbidden from steaming the envelopes open.

The client is backend-first on these two calls and falls back to direct-to-`github.com` on 5xx / network errors *only* — not on valid-but-negative responses like `authorization_pending` or `access_denied`, which are GitHub's real answers and `github.com` direct would say the same thing.

Rate limits: 10/hr/IP on `start`, 200/hr/IP on `poll`. The poll rate is generous because the device-flow polling cadence is GitHub's, not ours, and a slow user might poll 30+ times per code.

---

## The badge service

Most projects use [shields.io](https://shields.io) or `custom-icon-badges` for README badges. They're fine, but they're third-party services with their own rate limits and their own design vocabulary. The store's UI is Material You, and the obvious move was to make our own badges that match.

So we did. And then we made the renderer into a public endpoint.

### What you get

- **Server-rendered SVG**, no JavaScript, no font loading. ~1–2 KB per badge, gzipped.
- **12 hues × 3 shades** = 36 palette slots, transcribed verbatim from [`ziadOUA/m3-Markdown-Badges`](https://github.com/ziadOUA/m3-Markdown-Badges)'s `dynamicBadges/main.py`. All filled, no outlined variants. The hue legend:

  | # | Hue | # | Hue | # | Hue |
  |---|---|---|---|---|---|
  | 1 | red | 5 | green | 9 | purple |
  | 2 | orange | 6 | teal | 10 | pink |
  | 3 | yellow | 7 | indigo | 11 | magenta |
  | 4 | lime | 8 | blue | 12 | neutral |

  Variants: `1` dark/saturated, `2` medium, `3` pastel.

- **Eleven icons** drawn from Material Symbols Outlined (Apache-2.0): `download`, `package`, `groups`, `star`, `bolt`, `fdroid`, `new_releases`, `code`, `widgets`, `palette`, `android`.
- **HTTP caching** that any CDN respects: `public, max-age=3600, s-maxage=3600, stale-while-revalidate=86400`. ETag for conditional requests. `AutoHeadResponse` plugin so HEAD works.

### URL conventions

```
Per-repo       /v1/badge/{owner}/{name}/{kind}/{style}/{variant}
                 kind ∈ {release, stars, downloads}

Global         /v1/badge/{kind}/{style}/{variant}
                 kind ∈ {users, fdroid}

Static         /v1/badge/static/{style}/{variant}?label=...&icon=...
```

Optional query params on every variant: `label=` (override the default text), `height=` (24–60, default 30).

Per-repo badges read from Postgres first. If the requested column is null (e.g. you've asked for a `release` on a repo we haven't fetched yet), the service falls through to a live GitHub API call via `GitHubResourceClient`, which has its own short-TTL in-memory cache (`TtlCache`). On any failure the badge degrades gracefully — still renders, but with `Cache-Control: max-age=300` and a `503` status so a CDN won't pin a stale degraded result for an hour.

Global `users` is configured via `BADGE_USER_COUNT`. Global `fdroid` lives-fetches version data from `f-droid.org/api/v1/packages/{packageId}` through `FdroidVersionClient`.

### Live examples

These render from this server. Go ahead, copy them into your own README.

```html
<img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/release/9/1" alt="Latest release"/>
<img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/stars/3/1" alt="Stars"/>
<img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/downloads/5/2" alt="Downloads"/>
<img src="https://api.github-store.org/v1/badge/fdroid/5/1" alt="F-Droid"/>
<img src="https://api.github-store.org/v1/badge/static/10/1?label=Kotlin%20Multiplatform&icon=code" alt="Kotlin Multiplatform"/>
<img src="https://api.github-store.org/v1/badge/static/11/2?label=Material%20You&icon=palette" alt="Material You"/>
<img src="https://api.github-store.org/v1/badge/static/8/2?label=API%2024%2B&icon=android" alt="API 24+"/>
```

Rendered:

<p>
  <img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/release/9/1" alt="Latest release"/>
  <img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/stars/3/1" alt="Stars"/>
  <img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/downloads/5/2" alt="Downloads"/>
  <img src="https://api.github-store.org/v1/badge/fdroid/5/1" alt="F-Droid"/>
  <img src="https://api.github-store.org/v1/badge/static/10/1?label=Kotlin%20Multiplatform&icon=code" alt="Kotlin Multiplatform"/>
  <img src="https://api.github-store.org/v1/badge/static/11/2?label=Material%20You&icon=palette" alt="Material You"/>
  <img src="https://api.github-store.org/v1/badge/static/8/2?label=API%2024%2B&icon=android" alt="API 24+"/>
</p>

If you don't see them, the backend is down — which is allowed (see [Optional by design](#optional-by-design)). Try again later.

### Code

| File | Role |
|---|---|
| [`badge/BadgeRenderer.kt`](src/main/kotlin/zed/rainxch/githubstore/badge/BadgeRenderer.kt) | Pure SVG layout (text width approximation, icon path placement) |
| [`badge/BadgeColors.kt`](src/main/kotlin/zed/rainxch/githubstore/badge/BadgeColors.kt) | The 36-slot palette, transcribed from `ziadOUA/m3-Markdown-Badges` |
| [`badge/BadgeIcons.kt`](src/main/kotlin/zed/rainxch/githubstore/badge/BadgeIcons.kt) | Material Symbols Outlined paths, by name |
| [`badge/BadgeService.kt`](src/main/kotlin/zed/rainxch/githubstore/badge/BadgeService.kt) | Per-repo / global / static dispatch, DB-first with live fallback |
| [`badge/FdroidVersionClient.kt`](src/main/kotlin/zed/rainxch/githubstore/badge/FdroidVersionClient.kt) | Live F-Droid version lookup |
| [`badge/TtlCache.kt`](src/main/kotlin/zed/rainxch/githubstore/badge/TtlCache.kt) | Tiny in-process TTL map for live fallbacks |
| [`routes/BadgeRoutes.kt`](src/main/kotlin/zed/rainxch/githubstore/routes/BadgeRoutes.kt) | Three routes, ETag/304 handling, degraded-mode status code |

---

## Tech stack and project layout

Kotlin 2.1.20 on JVM 21. Ktor 3.1.2. Exposed 0.60. Koin 4. PostgreSQL 16 + HikariCP. Meilisearch 1.x. Sentry. Logback. Build with Gradle, ship as a fat JAR via Ktor's plugin.

```
src/main/kotlin/zed/rainxch/githubstore/
├── Application.kt          ── module() entry: Koin, plugins, routing, workers
├── AppModule.kt            ── Koin definitions (single { ... } for repos, clients, workers)
├── Plugins.kt              ── ContentNegotiation, CORS, RateLimit, CallLogging, etc.
├── BuildInfo.kt            ── reads /buildinfo.properties for /health version field
│
├── routes/                 ── one file per resource. Receive deps as parameters.
│   ├── Routing.kt          ── wires everything from Application.module()
│   ├── HealthRoutes.kt     ──  /v1/health
│   ├── SearchRoutes.kt     ──  /v1/search, /v1/search/explore
│   ├── CategoryRoutes.kt   ──  /v1/categories/{kind}/{platform}
│   ├── TopicRoutes.kt      ──  /v1/topics/{bucket}/{platform}
│   ├── RepoRoutes.kt       ──  /v1/repo/{owner}/{name}
│   ├── ReadmeRoutes.kt     ──  /v1/readme/{owner}/{name} (cached opaque proxy)
│   ├── UserRoutes.kt       ──  /v1/user/{username}      (cached opaque proxy)
│   ├── ReleasesRoutes.kt   ──  release listing for /repo
│   ├── EventRoutes.kt      ──  /v1/events  telemetry ingestion
│   ├── AuthRoutes.kt       ──  /v1/auth/device/{start,poll} stateless proxies
│   ├── BadgeRoutes.kt      ──  /v1/badge/...
│   └── InternalRoutes.kt   ──  /v1/internal/metrics  (X-Admin-Token gated)
│
├── db/                     ── Exposed DAO layer + Meilisearch + migrations
│   ├── DatabaseFactory.kt  ── Hikari + custom migration runner (no Flyway)
│   ├── Tables.kt           ── Exposed table objects
│   ├── RepoRepository.kt   ── findByCategory / findByTopicBucket / lookups
│   ├── SearchRepository.kt ── Meilisearch query orchestration
│   ├── SearchMissRepository.kt ── miss logging (sha256 of query)
│   ├── EventRepository.kt  ── batched inserts for /v1/events
│   ├── ResourceCacheRepository.kt ── README/user opaque-proxy cache
│   └── MeilisearchClient.kt ── HTTP client for the index (POST replace, PUT merge!)
│
├── ingest/                 ── everything that talks to GitHub
│   ├── GitHubSearchClient.kt    ── search/explore + token rotation + async persist
│   ├── GitHubResourceClient.kt  ── README/user/release fetching with caching
│   ├── GitHubDeviceClient.kt    ── used only by AuthRoutes
│   ├── SearchMissWorker.kt      ── hourly index warming
│   ├── SignalAggregationWorker.kt ── hourly score recompute
│   └── RepoRefreshWorker.kt     ── hourly stale-repo refresh
│
├── ranking/
│   └── SearchScore.kt      ── one function. Don't inline this anywhere.
│
├── badge/                  ── M3 SVG badge service (see above)
│   ├── BadgeRenderer.kt
│   ├── BadgeColors.kt
│   ├── BadgeIcons.kt
│   ├── BadgeService.kt
│   ├── BadgeStyle.kt
│   ├── FdroidVersionClient.kt
│   └── TtlCache.kt
│
├── metrics/
│   └── SearchMetricsRegistry.kt ── per-source counters, latency buckets
│
├── model/                  ── DTOs (Kotlinx Serialization)
│   ├── HealthResponse.kt
│   ├── SearchResponse.kt
│   ├── ExploreResponse.kt
│   ├── RepoResponse.kt
│   └── EventRequest.kt
│
└── util/
    └── Recency.kt          ── days-since-release helper

src/main/resources/
├── db/migration/           ── V1__init.sql, V2__..., etc. (added to migrations list in DatabaseFactory.kt)
├── application.conf        ── Ktor config (port, env)
└── logback.xml             ── log format, Sentry appender
```

---

## Local development

Postgres + Meilisearch via Docker; the app via Gradle on your host. The `docker-compose.override.yml` exposes `5432` and `7700` to localhost so you can poke at them.

```bash
# 1. start dependencies
docker compose up postgres meilisearch -d

# 2. build the fat JAR
./gradlew buildFatJar

# 3. run with dev env
DATABASE_URL="jdbc:postgresql://localhost:5432/githubstore" \
DATABASE_PASSWORD="githubstore" \
MEILI_URL="http://localhost:7700" \
MEILI_MASTER_KEY="devkey" \
java -jar build/libs/github-store-backend.jar
```

Or all-in-Docker:

```bash
docker compose up -d --build
```

For the fastest feedback loop while editing:

```bash
./gradlew compileKotlin   # ~3s incremental, catches type errors
```

### Required env vars

| Var | Required? | What it does |
|---|---|---|
| `POSTGRES_PASSWORD` | yes (docker) | Compose password for the Postgres container |
| `DATABASE_URL` | yes (host) | JDBC URL when running outside compose |
| `DATABASE_PASSWORD` | yes (host) | Postgres password when running outside compose |
| `MEILI_URL` | yes | Meilisearch base URL |
| `MEILI_MASTER_KEY` | yes | Meilisearch API key |
| `GITHUB_TOKEN` | recommended | Quiet-window fallback PAT |
| `GH_TOKEN_TRENDING` | recommended | Rotation pool slot 1 |
| `GH_TOKEN_NEW_RELEASES` | recommended | Rotation pool slot 2 |
| `GH_TOKEN_MOST_POPULAR` | recommended | Rotation pool slot 3 |
| `GH_TOKEN_TOPICS` | recommended | Rotation pool slot 4 |
| `GITHUB_OAUTH_CLIENT_ID` | yes for `/auth/device/*` | The OAuth App client_id; backend refuses to start without it if device routes are in play |
| `TOKEN_QUIET_START_UTC` | optional (default `1`) | Hour at which the rotation pool is bypassed |
| `TOKEN_QUIET_END_UTC` | optional (default `4`) | Hour at which the pool resumes |
| `ADMIN_TOKEN` | optional | Gates `/v1/internal/metrics`; open if unset (fine for local dev) |
| `SENTRY_DSN` | optional | Sentry init is a no-op if unset |
| `BADGE_USER_COUNT` | optional | The number `/v1/badge/users/...` displays |

`/v1/health` returns the build version (read from `buildinfo.properties` generated by Gradle) and the up/down status of Postgres and Meilisearch:

```bash
$ curl -s http://localhost:8080/v1/health
{"status":"ok","version":"0.1.0","postgres":"up","meilisearch":"up"}
```

---

## Deployment

The whole thing deploys with one shell script:

```bash
./deploy.sh <your-vps-ip>
```

Under the hood that's:

1. `rsync` the project to `/opt/github-store-backend/` on the VPS, excluding `.git/`, `build/`, `.env`, `.claude/`, and `docker-compose.override.yml`. The `.env` lives only on the VPS — never in git.
2. SSH in and run `docker compose -f docker-compose.prod.yml up -d --build`.
3. Reload Caddy in place (`caddy reload`), falling back to `docker compose restart caddy` if reload fails. Reload, not restart, because a Caddy restart causes a TLS reconnect blip for live traffic.
4. Wait 15s, hit `/v1/health`, log "Health check failed!" if it doesn't come back.

`docker-compose.prod.yml` differs from the dev compose in three ways: it does **not** expose Postgres or Meilisearch ports to the host (only Caddy on 80/443), it bind-mounts `Caddyfile.prod` and `/opt/github-store-backend/.env`, and Postgres binds to `127.0.0.1:5432` so the operator can SSH-tunnel into it from a laptop.

### The two hostnames

`Caddyfile.prod` defines two virtual hosts:

- **`api.github-store.org`** — the primary. Normally fronted by Gcore CDN (free plan), which pulls from this host as origin. Caddy issues a Let's Encrypt cert here too, in case Gcore ever pass-throughs TLS or we need to cut traffic over directly in an outage.
- **`api-direct.github-store.org`** — straight at the Hetzner IP, bypasses any CDN. Used as (a) operator diagnostic, (b) client-side fallback when the CDN path times out, and (c) the origin for Gcore itself.

Both reverse-proxy to `app:8080`, both issue Let's Encrypt certs, both rewrite `X-Forwarded-For` to the real TCP source so a forged header can't rotate past the rate limiter.

### One subtle Gcore detail

Gcore's origin-pull protocol must be **HTTPS**, not HTTP. HTTP triggers Caddy's automatic redirect to HTTPS, which leaks the direct hostname to the CDN and breaks pulling. If you set up a CDN in front of this and your origin is Caddy, double-check this.

### Companion services

- **Python fetcher** at `/opt/fetcher/` runs via cron — full fetch daily 02:00 UTC, new-releases every 3h. Writes into the same Postgres + Meilisearch the backend reads. Source: `/Users/rainxchzed/Documents/development/backend/api`.
- **Static fallback** is a separate repo, `OpenHub-Store/api`. The fetcher (or a sibling job) writes JSON files there that the client reads when this backend is unreachable.

---

## Operational notes and war stories

The kind of footgun knowledge that's only useful after you've stepped on it.

### Meilisearch: PUT for partial updates, never POST

`MeilisearchClient.addDocuments()` is `POST /indexes/{}/documents`. On Meili, that's a *replace*: every field you don't send becomes null. `MeilisearchClient.updateScores()` is `PUT /indexes/{}/documents`. That's a *merge*: unset fields are preserved.

If you ever add a "partial update" path — for example, "every hour I want to push just `{id, search_score}` for 3,000 docs" — and you reach for the closest-looking method (`addDocuments`), you will wipe `name`, `description`, `topics`, and everything else on every document you touch. Verify the HTTP verb before deploying.

This was a real near-miss during the ranking refactor. The fix was a one-line method rename plus a sternly-worded comment.

### No Flyway

We tried. Flyway 11 community edition rejected the standard `V1__init.sql` filename ("invalid resource name"), threw on startup, refused to apply migrations, and was generally allergic to being embedded in a Ktor app.

So `DatabaseFactory.runMigrations()` is a 30-line custom runner. It reads SQL files from `src/main/resources/db/migration/` and executes them through Exposed. V1 is idempotent (a `CREATE TABLE IF NOT EXISTS` plus a check for one of its columns). V2+ are additive — `ALTER TABLE ADD COLUMN IF NOT EXISTS`, etc. — wrapped in try/catch so a no-op migration on a fresh database doesn't crash the app.

When you add a new migration, you must also add it to the `migrations` list in `DatabaseFactory.kt`. That's the only place that knows what V-numbers exist.

### No `Route.inject()`

Koin ships an `inject()` extension for Ktor `Route`. Under Ktor 3 it references a class that's been moved, and using it produces a runtime `NoClassDefFoundError`. Not a compile error. A startup-time landmine.

The fix is structural: dependencies (repositories, clients, services) are all `single { ... }` in `AppModule.kt`. They get pulled out at the `Application` level in `routes/Routing.kt` via `application.get()`, and passed into route functions as parameters:

```kotlin
fun Application.module() {
    val repoRepository: RepoRepository = get()
    val searchRepository: SearchRepository = get()
    val searchClient: GitHubSearchClient = get()
    routing {
        route("/v1") {
            categoryRoutes(repoRepository)
            searchRoutes(searchRepository, searchClient)
            // ...
        }
    }
}
```

Slightly more typing than `inject()`, but it works under Ktor 3 today and it makes the dependency surface of every route file explicit. The three workers — `SearchMissWorker`, `SignalAggregationWorker`, `RepoRefreshWorker` — are likewise `single { ... }` and started from `Application.module()`.

### Rate limit key order

The rate limiter reads `CF-Connecting-IP` first, then `X-Forwarded-For`'s first IP, then the socket peer. The `CF-Connecting-IP` branch is leftover from a pre-Gcore era and is currently dead in production (Gcore doesn't set it). It stays for one reason: when we eventually swap CDN providers, we don't want every user in the world collapsing into one rate-limit bucket because the new CDN happens to set `X-Forwarded-For` to the same edge IP for everyone behind a given pop. Belt and suspenders.

Caddy actively *overwrites* `X-Forwarded-For` with the real TCP source (`request_header X-Forwarded-For {remote_host}`) so a malicious client can't forge it from outside. The CDN sets it correctly when present.

### Adding a new field to `RepoResponse` is a five-place edit

If you add a column to the repo model, all five of these have to agree or data is silently dropped on the floor:

1. `Tables.kt` — Exposed schema
2. `RepoRepository.toRepoResponse()` — Postgres → DTO mapper
3. `MeiliRepoHit` (in `SearchRepository.kt`) — Meilisearch document shape
4. `SearchRoutes` mapper — Meilisearch hit → DTO
5. The Python fetcher's `meili_sync.py` — Postgres → Meilisearch field selection

Forget any one of them and the field will be present in some responses and absent in others, depending on which of the three search paths produced the result. Ask me how I know.

### Don't log secrets

Never log raw search queries, device IDs, or `X-GitHub-Token` values. Hash queries and device IDs via `SearchMissRepository.sha256()`. Treat tokens as fissile material — no logging, no Sentry breadcrumbs, no metrics labels, ever.

---

## Attribution

- Badge palette is transcribed from [`ziadOUA/m3-Markdown-Badges`](https://github.com/ziadOUA/m3-Markdown-Badges) — same hex values, same hue order. Their project was the inspiration; ours is the server-side rendition. Apache-2.0.
- Badge icons are from [Material Symbols](https://fonts.google.com/icons) (Outlined). Apache-2.0.
- The KMP client app lives at [`OpenHub-Store/GitHub-Store`](https://github.com/OpenHub-Store/GitHub-Store).

The badge endpoints at `https://api.github-store.org/v1/badge/...` are a public HTTP service — feel free to use them in your own READMEs.
