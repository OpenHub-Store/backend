# Client migration: forward the user's GitHub token on search

Audience: the KMP client at `/Users/rainxchzed/Documents/development/kmp/GitHub-Store`.
Goal: on every call to `/v1/search` and `/v1/search/explore`, send the user's GitHub personal access token in the `X-GitHub-Token` header (if one is available on the device) so the backend's on-demand GitHub passthrough runs under the user's authenticated quota (5000 req/hr) instead of sharing one backend-wide or unauthenticated quota.

## Why this matters

When a search has fewer than 5 local results, the backend falls back to searching GitHub live and ingesting the findings into Postgres + Meilisearch. Every successful passthrough permanently warms the index for the next user searching anything similar. Running that passthrough under the searcher's own token means:

- No shared rate ceiling — each user's quota is their own.
- The shared index improves proportionally to user activity.
- No secrets or service PATs need to live on the backend.

## What changed on the backend

- `GET /v1/search` — now reads optional `X-GitHub-Token` header.
- `GET /v1/search/explore` — now reads optional `X-GitHub-Token` header.
- All other endpoints unchanged.
- Behavior is fully backward compatible: no header → backend falls back to its env `GITHUB_TOKEN` (if set) or unauthenticated (10 req/min, may fail). Sending the header just makes the passthrough faster and more reliable for that user.

## What to do in the client

1. **Locate where the user's GitHub PAT is stored.** The app already uses the GitHub REST API directly as a fallback when the backend is unreachable — reuse that same token source. If the app currently runs GitHub unauthenticated, this migration is the right moment to introduce a "Connect your GitHub account" flow (optional, skippable); users who skip simply get the pre-migration behavior.

2. **On every `/v1/search` and `/v1/search/explore` request**, set the header when a token is available:

   ```kotlin
   val token = githubTokenStore.currentToken() // null if user hasn't connected
   httpClient.get("$backendBaseUrl/v1/search") {
       parameter("q", query)
       platform?.let { parameter("platform", it) }
       if (!token.isNullOrBlank()) {
           header("X-GitHub-Token", token)
       }
   }
   ```

   Do **not** send the header with empty/blank value — either send a real token or omit the header entirely.

3. **Never send the token to any other endpoint.** Only `/v1/search` and `/v1/search/explore` consume it. Sending it to `/v1/categories/*`, `/v1/topics/*`, `/v1/repo/*`, `/v1/events`, or `/v1/health` is pointless and widens the leak surface.

4. **Respect TLS.** The backend is always HTTPS (`https://api.github-store.org`). If you ever hit it over plain HTTP — refuse to send the header.

## Security constraints

- **Never log the token.** Scrub it from any request/response logging, crash reports (Crashlytics/Sentry on the client), or analytics events. Interceptors that log headers must redact `X-GitHub-Token`.
- **Store the token in platform-secure storage.** Android: EncryptedSharedPreferences or Keystore-wrapped. Desktop: system keychain (macOS Keychain, Windows Credential Manager, libsecret on Linux). No plaintext in prefs/JSON files.
- **Never persist the token in the Meilisearch/Postgres fallback cache JSON files** (the ones in `OpenHub-Store/api`). Those are public-read.
- **Scope the token minimally.** The app only needs `public_repo` scope (or no scopes for read-only public access via fine-grained PAT). Surface this requirement in any UI that asks the user to paste a token.
- **Handle invalid/revoked tokens gracefully.** If the user's token is rejected, the backend still returns search results (just without the GitHub passthrough boost), so there's nothing the client needs to do at the response level. But if the client detects a 401 from its own direct GitHub REST fallback path, prompt the user to reconnect — and stop sending that token to the backend until they do.

## How to verify the migration

1. Connect a GitHub account in the client.
2. Search for a deliberately niche term unlikely to be in the index (e.g. `"obscure-kmp-utility-12345"`).
3. Inspect the network request: confirm the `X-GitHub-Token` header is present and the value matches the stored token.
4. Inspect the response: if the `source` field reads `"meilisearch+github"`, the passthrough ran and your token was used.
5. Disconnect the account and repeat step 2. Confirm the header is absent from the request. The response should still succeed (just may be slower or lower-quota).
6. Check logs/crash reports after a full session: grep for the token string — zero hits.

## Timeouts on `/v1/search/explore`

This endpoint fans out to GitHub's releases API and paginates per repo. Cold-path latency is routinely 10–30 seconds on the first call for a megaproject (projects with 1000+ releases). Configure this endpoint's socket and request timeouts to **30 seconds** explicitly — the default 5-second client timeout will reliably fail with `SocketTimeoutException`.

```kotlin
httpClient.get("$backendBaseUrl/v1/search/explore") {
    timeout { requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
    parameter("q", query)
    parameter("page", page)
    if (!token.isNullOrBlank()) header("X-GitHub-Token", token)
}
```

`/v1/search` itself stays on the normal 5-second budget.

## Interpreting empty results on `/v1/search`

The response now carries a `passthroughAttempted: Boolean` field. Use it to branch zero-result UX:

- `items=[], passthroughAttempted=true` → GitHub's live search also found nothing. Show "No repositories found."
- `items=[], passthroughAttempted=false` → index was warm with no hits, but the backend didn't fire the GitHub passthrough (e.g. `offset > 0`). Offer the user an explicit "Find more on GitHub" action that calls `/v1/search/explore`.

## Known discovery limit

Repos with fewer than ~10 GitHub stars that aren't already in our curated index may not surface through text search, because they get ranked below larger library projects even on an exact-name query. The backend now runs a second `in:name`-biased pass when the primary pass yields zero installables, which recovers most niche apps — but some very new / unknown repos will still be invisible until they accumulate more stars or appear in a curated topic run. Client UX should not assume every GitHub repo is findable via search.

## Out of scope for this migration

- The `/v1/events` telemetry endpoint.
- Any change to the offline/fallback JSON behavior.
- Backend auth unrelated to GitHub (there is none at present).
