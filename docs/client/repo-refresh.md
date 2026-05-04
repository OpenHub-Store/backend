# Client Integration — `POST /v1/repo/{owner}/{name}/refresh`

**Audience:** client coding agent (KMP / Compose Multiplatform).
**Goal:** wire the existing details-screen pull-to-refresh / refresh button to the new backend endpoint so users can force-fresh repo data on demand.

---

## 1. What the endpoint does

Refetches the named repo's metadata + latest release from GitHub, upserts the curated DB row, pushes the update to Meilisearch, and returns the live data.

Counterpart to the cache-first `GET /v1/repo/{owner}/{name}`. Use this when the user *explicitly asks* for fresh data — never auto-fire on screen open.

---

## 2. Request

```
POST https://api.github-store.org/v1/repo/{owner}/{name}/refresh
```

### Path params

- `owner` — GitHub login (regex on backend: `^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$`).
- `name` — repo name (regex on backend: up to 100 chars, broader allowed-char set).

Both are validated server-side; bad values return `400 invalid_owner` / `400 invalid_name`.

### Headers

| Header | Required | Notes |
|--------|----------|-------|
| `X-GitHub-Token` | optional | Same semantics as on the cache-first GET — if present, backend uses the user's PAT for the upstream call (5000/hr quota). If absent, backend uses its 4-PAT rotation pool (anon users still work). |
| `Content-Type` | omit | No request body. |

### Body

Empty. Don't send a JSON body.

### Verb

POST. Not GET. Don't try `?refresh=true` — Cloudflare would cache the trigger.

---

## 3. Response shapes

### 200 OK — success

Body is identical in shape to the GET path's `RepoResponse`. Same DTO.
`Cache-Control: no-store` — never cache this response on the client beyond what the user is currently viewing.

```json
{
  "id": 12345,
  "name": "refined-github",
  "fullName": "sindresorhus/refined-github",
  "owner": { "login": "sindresorhus", "avatarUrl": "..." },
  "description": "...",
  "stargazersCount": 12345,
  "forksCount": 678,
  "language": "TypeScript",
  "topics": ["browser-extension", "github"],
  "releasesUrl": "https://github.com/sindresorhus/refined-github/releases",
  "updatedAt": "2026-04-15T12:00:00Z",
  ...
}
```

Render this directly. Don't follow up with a GET — you'd just hit the CDN's stale copy.

### 429 Too Many Requests — cooldown

```json
{ "error": "cooldown", "message": "Try again in 24s" }
```

Headers: `Retry-After: <seconds>`.

Cause: same `(owner, name)` was refreshed less than 30 seconds ago by **anyone** (the cooldown is global per repo, not per user). Show a non-dismissive UI hint: "You just refreshed this — try again in Ns." Disable the refresh button and re-enable it after `Retry-After` elapses.

### 429 Too Many Requests — budget exhausted

```json
{ "error": "budget_exhausted", "message": "Refresh budget exhausted, try again in 1234s" }
```

Headers: `Retry-After: <seconds>`.

Cause: backend hit its global hourly refresh budget (1000/hr across all repos). Rare. Treat as "service is busy — try later." Don't retry automatically. Show a generic "Try again later" toast.

### 404 Not Found

```json
{ "error": "not_found" }
```

Cause: the repo doesn't exist on GitHub anymore (deleted or renamed). Tell the user the repo is gone. If your DB row still references it, mark it stale on the client side.

### 410 Gone — archived

```json
{ "error": "archived" }
```

Cause: the repo is archived or disabled on GitHub. Update your local view to reflect that ("This repo has been archived."). Don't re-fire the refresh — backend will keep returning 410 until the repo is unarchived.

### 502 Bad Gateway — upstream unreachable

```json
{ "error": "github_unreachable" }
```

Cause: backend tried to reach GitHub and failed (network blip, GitHub outage, all 4 pool tokens hit GitHub's secondary rate limit, etc.). Show a transient error toast: "Couldn't refresh. Try again shortly." User can retry — backend will not be in cooldown for them since the failed attempt didn't successfully refresh.

> **Subtle:** the backend records the cooldown timestamp **before** the upstream call, so a user who hits 502 actually IS in cooldown for 30s. If you want to let them retry sooner on transient errors, surface "Try again in 30s" rather than implying immediate retry. Pragmatic: show a generic "Try again" with no countdown — most transient errors resolve in seconds anyway, and the 30s cap is short.

### 400 invalid_owner / 400 invalid_name

```json
{ "error": "invalid_owner" }
```

Cause: the path-params didn't pass the regex on the backend. Should never happen if you only fire refresh on a repo you got from a GET — both endpoints share the same validation. Treat as a programming bug; log + don't retry.

### 401, 403

Should never happen for this endpoint. The backend remaps any upstream 401 to 502 (per the existing `/repo` GET contract). If you see one, file a bug.

---

## 4. Rate-limiting hierarchy (for designing your UX)

| Layer | Limit | Effect on user |
|-------|-------|----------------|
| Per-repo cooldown | 30s per `(owner, name)` global | One user → 1 refresh per repo per 30s. Two users hitting the same repo within 30s → second user sees 429. |
| Backend search bucket | 240 / min / (token-or-IP) | If the user hits ANY of `/search`, `/releases`, `/readme`, `/user`, `/users/*`, `/repo/*/refresh` enough times in a minute, gets 429. Refreshes count toward this. |
| Backend global bucket | 360 / min / IP | Wider net; rarely the binding limit since per-route caps fire first. |
| Pool budget | 1000 refreshes / hr globally | Shared across all users. Rare-corner trigger; treat as transient error. |

Practical: for one user clicking refresh once on a few repos per minute, none of these limits fire. Spam-clicking the same repo trips per-repo cooldown immediately (good — you wanted that anyway).

---

## 5. Suggested UX

### Pull-to-refresh on details screen

```
1. User pulls.
2. Show loading indicator.
3. POST /v1/repo/{owner}/{name}/refresh with the user's stored X-GitHub-Token if logged in.
4. On 200: replace currently-displayed data with response body. Stop indicator.
5. On 429 (cooldown): stop indicator. Show inline hint "Refreshed Ns ago" using Retry-After.
6. On 4xx/5xx other than cooldown: stop indicator. Toast appropriate error from §3.
```

### Explicit "Refresh" button

Same flow. Disable the button immediately on click. Re-enable after the response (or after `Retry-After` elapses on 429).

### Optimistic UI

Don't. The whole point of refresh is to surface the new data — show old data + spinner, then swap to new data on response. Optimistic empty state is worse UX than "still loading."

### Don't auto-retry

Refresh is a user-intent action. Auto-retry on failure violates the implicit contract ("the user clicked once, the server got the request once"). Show the error, let them click again.

### Don't store `Retry-After` longer than the screen

If the user navigates away and comes back, the cooldown on the server may have elapsed. Don't pre-disable the button based on a stale Retry-After.

---

## 6. Pseudo-code (Kotlin / KMP HttpClient)

```kotlin
suspend fun refreshRepo(owner: String, name: String): RefreshOutcome {
    val response = httpClient.post {
        url("https://api.github-store.org/v1/repo/$owner/$name/refresh")
        userTokenStore.current()?.let { header("X-GitHub-Token", it) }
    }

    return when (response.status.value) {
        200 -> RefreshOutcome.Ok(response.body<RepoResponse>())
        429 -> {
            val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: 30L
            val body = response.body<ErrorBody>()
            when (body.error) {
                "cooldown" -> RefreshOutcome.Cooldown(retryAfter)
                "budget_exhausted" -> RefreshOutcome.BudgetExhausted(retryAfter)
                else -> RefreshOutcome.RateLimited(retryAfter)
            }
        }
        404 -> RefreshOutcome.NotFound
        410 -> RefreshOutcome.Archived
        502 -> RefreshOutcome.UpstreamError
        in 400..499 -> RefreshOutcome.ClientError(response.status.value)
        in 500..599 -> RefreshOutcome.ServerError(response.status.value)
        else -> RefreshOutcome.UnknownError(response.status.value)
    }
}

@Serializable
data class ErrorBody(val error: String, val message: String? = null)

sealed class RefreshOutcome {
    data class Ok(val repo: RepoResponse) : RefreshOutcome()
    data class Cooldown(val retryAfterSeconds: Long) : RefreshOutcome()
    data class BudgetExhausted(val retryAfterSeconds: Long) : RefreshOutcome()
    data class RateLimited(val retryAfterSeconds: Long) : RefreshOutcome()
    data object NotFound : RefreshOutcome()
    data object Archived : RefreshOutcome()
    data object UpstreamError : RefreshOutcome()
    data class ClientError(val status: Int) : RefreshOutcome()
    data class ServerError(val status: Int) : RefreshOutcome()
    data class UnknownError(val status: Int) : RefreshOutcome()
}
```

Use `RefreshOutcome` directly in your ViewModel / state holder. Don't blanket-throw on non-200 — each outcome has different UX.

---

## 7. Backend-fallback consideration

The KMP client is backend-first with a direct-to-GitHub fallback for some flows (per `CLIENT_MIGRATION_X_GITHUB_TOKEN.md`). The refresh endpoint **does not have a fallback path** — there's no equivalent direct-to-GitHub call that does upsert + Meili push. If `api.github-store.org` is unreachable:

1. Show a transient error.
2. Optionally: try the same `POST` against `https://api-direct.github-store.org/v1/repo/{owner}/{name}/refresh` (same backend, CDN bypass). Only on 5xx / network errors — not on valid-but-negative responses like 429.
3. If that also fails: don't fall through to GitHub directly. Just tell the user refresh isn't available right now.

---

## 8. What you can skip

- No need to handle 401 / 403 — backend won't return them for this endpoint.
- No need to read `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset` proactively for refresh. The 30s cooldown is the binding constraint, surfaced via `Retry-After` only when you actually hit it.
- No need to debounce client-side. The backend's per-repo cooldown is the truth — your UI just has to handle the 429 gracefully.
- No need to cache the response client-side beyond the lifetime of the screen. `Cache-Control: no-store` is informational; the bigger reason is the data is already in your in-memory state from the response.

---

## 9. Things explicitly out of scope (don't try to wire these)

- "Refresh categories / trending / topics on detail open." Backend explicitly does NOT do this. Those are batch-ranked.
- "Refresh README / user profile / releases as part of repo refresh." Each has its own GET; if you want them fresh, add a separate refresh button per surface (or just rely on their existing TTLs — they age out fast).
- "Show estimated remaining refreshes." The 1000/hr global budget is shared; per-user view of remaining isn't surfaced because it's not per-user. Treat budget exhaustion as a transient error.

---

## 10. Authoritative reference

The contract above is the definitive interface for client work. Backend implementation lives at `OpenHub-Store/backend` in:

- `src/main/kotlin/zed/rainxch/githubstore/routes/RepoRefreshRoutes.kt` — the route handler
- `src/main/kotlin/zed/rainxch/githubstore/ingest/RepoRefreshCoordinator.kt` — gating logic

If this doc and the backend behavior diverge, **the backend wins** — file an issue on the backend repo and update this doc to match.
