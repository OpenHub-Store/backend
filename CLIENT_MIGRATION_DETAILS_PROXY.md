# Client migration: backend-proxied Details-screen data (Phase 5.1)

Audience: the KMP client's `DetailsRepositoryImpl` and friends.
Goal: route `/v1/releases/{owner}/{name}` through the backend first, and rely on the extended `/v1/repo/{owner}/{name}` to work for *any* repo (not just curated ones). Both endpoints are now China-reachable via Gcore + `api-direct.github-store.org` fallback.

## What changed on the backend

Two live endpoints + one extended endpoint. All three cache-hit cheaply; all three fall back to direct-to-GitHub-from-the-client is still the right client behavior on backend infrastructure failure.

### `GET /v1/releases/{owner}/{name}` — new

Opaque pass-through for `https://api.github.com/repos/{owner}/{name}/releases`. Backend caches the verbatim JSON body for 1 hour, with ETag-based revalidation keeping popular repos free of quota burn.

**Query params (optional):**
- `page` — 1..50, default 1
- `per_page` — 1..100, default 100

**Request headers (optional):**
- `X-GitHub-Token` — user's PAT, forwarded upstream. Same mechanism as `/v1/search`. When present, the call runs on the user's 5000/hr quota. When absent, backend uses its own token pool.

**Responses:**

| Status | Body | Meaning |
|---|---|---|
| 200 | GitHub's JSON array, verbatim | Cache hit or fresh fetch |
| 404 | `{"error":"upstream_404"}` | Repo doesn't exist (cached 15 min) |
| 502 | `{"error":"github_unreachable"}` | Backend couldn't reach GitHub; client should retry via direct-to-GitHub |

**Edge cache:** `Cache-Control: public, max-age=30, s-maxage=60` on hits. No-store on stale-fallback responses (served when upstream is temporarily down and the backend is serving last-known-good from its own cache).

**Response header `X-Cache-State: stale-fallback`** is set when the backend is returning a cached body because GitHub was unreachable at revalidation time. Client can treat this as a successful response (data is still GitHub-sourced, just possibly an hour old). Optional: surface a subtle "may be slightly out of date" chip in UI.

### `GET /v1/repo/{owner}/{name}` — extended

**Before Phase 5.1:** pure DB lookup; returned 404 for any non-curated repo.
**Now:** DB hit returns immediately (unchanged); on miss, backend fetches repo metadata from GitHub, caches 24h, returns a partial `RepoResponse`.

The shape is the same `RepoResponse` the client already parses. The important change is that **the release / installer / download fields will be null or default-zero for lazy-cached (non-curated) repos**. Client must already tolerate nulls on those fields since the shape has used `Int? = null` / `Boolean = false` defaults throughout.

To populate release info after loading a lazy-cached repo, the client calls `/v1/releases/{owner}/{name}` (new endpoint above) and hydrates the installer flags client-side from the release assets — same logic the backend uses in `detectPlatforms`.

Same `X-GitHub-Token` forwarding, same 502-on-upstream-error contract.

## What to do in the client

### 1. Add backend methods to `BackendApiClient.kt`

```kotlin
suspend fun getReleases(
    owner: String,
    name: String,
    page: Int = 1,
    perPage: Int = 100,
    userToken: String? = null,
): HttpResponse = httpClient.get("$backendBaseUrl/v1/releases/$owner/$name") {
    parameter("page", page)
    parameter("per_page", perPage)
    if (!userToken.isNullOrBlank()) header("X-GitHub-Token", userToken)
    // Release fetch can be slow on cold path (backend is going to GitHub for
    // you + possibly filtering + paginating). 15s is enough for 99th-percentile
    // cold-path; increase to 30s if you see SocketTimeouts in Sentry.
    timeout { requestTimeoutMillis = 15_000; socketTimeoutMillis = 15_000 }
}
```

`getRepositoryByOwnerAndName` already exists and already uses the backend-first pattern — no change to the HTTP call itself, just remove any error-swallowing that happens when the backend used to return 404 for non-curated repos. **On Phase 5.1 the backend will not 404 for existing-on-GitHub repos anymore**, so any "we got 404, don't bother retrying or showing UI" paths can be dropped.

### 2. Update `DetailsRepositoryImpl.getAllReleases`

Wrap with a backend-first try → direct-to-GitHub fallback, identical shape to `getRepositoryByOwnerAndName`:

```kotlin
override suspend fun getAllReleases(owner: String, name: String): Result<List<GithubRelease>> = runCatching {
    val userToken = tokenStore.currentToken()?.accessToken
    val backendResult = runCatching {
        backendApi.getReleases(owner, name, userToken = userToken)
    }.mapCatching { response ->
        if (response.status.value in 500..599) error("backend 5xx, fallback to direct")
        if (!response.status.isSuccess()) error("backend ${response.status.value}")
        response.body<List<GithubReleaseDto>>().map { it.toDomain() }
    }

    backendResult.getOrElse {
        // Infrastructure error — fall back to direct-to-GitHub (existing path)
        githubApi.getReleases(owner, name).map { it.toDomain() }
    }
}
```

Same fallback rule as the auth migration: **only fall back on 5xx / network errors.** A 404 from the backend means GitHub also returned 404 (cached by the backend as a negative hit) — direct-to-GitHub would give the same 404 back.

### 3. Nothing else changes on the client for Phase 5.1

`/v1/readme/*` and `/v1/user/*` are coming in Phase 5.2 and 5.3. The corresponding client methods stay on their current direct-to-GitHub path for now.

## Rate limits

- `/v1/repo/{owner}/{name}` — inherits the global 120/min/IP limit.
- `/v1/releases/{owner}/{name}` — reuses the `search` rate-limit bucket (60/min/IP). Higher-cost endpoint, capped more tightly than the global.

These key off the real TCP source IP (Caddy overwrites client-supplied `X-Forwarded-For`), so a forged-header retry strategy will not buy extra quota. Architect retry with real backoff.

## Edge + client cache stack

Today's stack after Phase 5.1:

```
   KMP client cache  (6h TTL, stale-fallback enabled)
        │ miss
        ▼
   Gcore edge       (30-60s, so momentarily-hot keys never hit origin)
        │ miss
        ▼
   Backend          /v1/repo:  24h cache + ETag
                    /v1/releases: 1h cache + ETag
        │ revalidate or miss
        ▼
   GitHub API       (user's token if provided, rotation pool otherwise)
```

For a fresh client install, expect:
- First `/v1/repo` call cold: ~300-800ms (backend went to GitHub).
- First `/v1/releases` call cold: ~600-1500ms (backend went to GitHub, possibly paginated).
- Second call within 6h: instant (client cache).
- Second call after 6h but within backend 1h TTL: ~60ms (backend cache hit).
- Second call within backend 24h (repo metadata) but after client 6h: ~60ms.

## Known edge cases

1. **Repo renamed on GitHub.** Backend cache holds the old body for up to 24h. Client's cached data is newer anyway; stale cache self-corrects on next revalidation. Low-severity.
2. **Private repo fetched with a user token that doesn't have access.** Backend caches the 404 for 15 min. A different user with the right token fetching the same repo right after would also get 404 from the cache. Collateral — fix is to key the cache by `(key, token-fingerprint)` for private repos. **Deliberately NOT implemented v1** — app is for public repos, this is a theoretical-only edge.
3. **GitHub outage.** Backend serves `X-Cache-State: stale-fallback` responses from its own cache as long as entries are < 30 days old. After that, clients get 502 → direct-GitHub fallback → also fails if GitHub is out. Expected and correct.

## Verification

After deploy you can smoke-test yourself without a token:

```bash
# Releases endpoint
curl -s "https://api.github-store.org/v1/releases/microsoft/vscode?per_page=5" | jq '.[0].tag_name'

# Backend-direct if CDN is throttled
curl -s "https://api-direct.github-store.org/v1/releases/microsoft/vscode?per_page=5" | jq '.[0].tag_name'

# Repo metadata for a NON-curated repo (should now succeed)
curl -s "https://api-direct.github-store.org/v1/repo/microsoft/vscode" | jq '.fullName'

# Confirm negative caching works
curl -s "https://api-direct.github-store.org/v1/repo/nonexistent-owner-xyz/fake-repo-123" -w "\nHTTP %{http_code}\n"
# Expect: 404 with {"error":"Repo not found"}, cached 15 min
```

## Out of scope

- `/v1/readme/*` (Phase 5.2)
- `/v1/user/*` (Phase 5.3)
- `/v1/repo/by-id/{id}` (not planned — confirmed unused by client)
- Client-side ETag revalidation (future enhancement; client cache doesn't yet plumb ETags)
