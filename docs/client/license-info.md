# Client Integration — `licenseSpdxId` / `licenseName`

**Audience:** client coding agent (KMP / Compose Multiplatform).
**Goal:** surface a repo's license on the details screen using the new `licenseSpdxId` + `licenseName` fields on `RepoResponse`. No new endpoint, no extra fetch — the values ride on the existing GET response.

---

## 1. What changed

`RepoResponse` now carries two new fields:

```kotlin
val licenseSpdxId: String? = null,   // e.g. "MIT", "GPL-3.0", "Apache-2.0"
val licenseName: String? = null,     // e.g. "MIT License", "GNU General Public License v3.0"
```

Both nullable — not every repo has a license. Old clients that don't know the fields parse cleanly via `ignoreUnknownKeys = true`. Same back-compat story as every other additive `RepoResponse` field.

---

## 2. What the values mean

GitHub's `license` object on `/repos/{owner}/{name}`. Backend only persists two fields out of GitHub's full payload:

- `licenseSpdxId` — the SPDX short tag. Stable, machine-readable, suitable for icon mapping or filter chips. Example: `"MIT"`, `"GPL-3.0"`, `"Apache-2.0"`, `"BSD-3-Clause"`, `"AGPL-3.0"`, `"MPL-2.0"`, `"Unlicense"`.
- `licenseName` — the full human name. Use for tooltips, accessibility labels, "About" sections. Example: `"MIT License"`, `"GNU General Public License v3.0"`.

### When both are null

GitHub returns `license: null` if:
- The repo has no `LICENSE` / `LICENSE.txt` / `LICENSE.md` file at the root.
- GitHub's classifier couldn't recognise the file's content (rare; usually means a custom or modified license).
- The repo is private + you don't have access (not a concern here — backend always uses authenticated calls).

Show "No license" or hide the chip entirely when both are null. **Do NOT** assume "unlicensed" means "free to use" — most popular OSS without a `LICENSE` file is still under default copyright. Do NOT ship UI that implies otherwise.

### When one is set but the other is null

Should not happen — backend writes both columns from the same GitHub object atomically. If you see it, it's a row written before V15 deployed and not yet refreshed. Treat as if both are null until refreshed.

---

## 3. Where the fields appear

Every `RepoResponse`-shaped payload, identical surface to `openIssuesCount`:

| Endpoint | Behaviour |
|----------|-----------|
| `GET /v1/repo/{owner}/{name}` | DB-hit and lazy-fetch paths both fill it. |
| `POST /v1/repo/{owner}/{name}/refresh` | Fresh from GitHub. |
| `GET /v1/categories/.../...` | DB value. |
| `GET /v1/topics/.../...` | DB value. |
| `GET /v1/search?q=...` | Meilisearch index value. |

Existing curated rows have `null` license fields until refreshed — backend writes them on:
1. Search-passthrough ingest
2. Refresh button
3. Hourly worker
4. Daily Python fetcher (after fetcher repo is updated)

---

## 4. Display recommendations

- **Where:** details screen, in the "facts" row alongside language, stars, forks, open issues. Or in an info panel.
- **Chip text:** show `licenseSpdxId` ("MIT", "GPL-3.0"). Short, scannable.
- **Tooltip / long-press:** show `licenseName` ("MIT License").
- **Tap behaviour:** open `https://github.com/{owner}/{name}/blob/HEAD/LICENSE` in a browser. Almost every licensed repo has a top-level `LICENSE` file. If GitHub redirects (because it's actually `LICENSE.md` or `COPYING`), browsers handle it.
- **Icon:** generic license / scale glyph. Some clients map specific licenses to specific icons (MIT = open lock, GPL = copyleft symbol). Optional polish — `licenseSpdxId` is the key.
- **Color:** don't color-code by permissive vs copyleft vs proprietary. That implies a value judgement and tends to be controversial. Neutral chip styling.
- **Null handling:** hide the chip cleanly. Don't render "Unknown license" — that's misleading.

---

## 5. Pseudo-code

```kotlin
@Composable
fun LicenseChip(repo: RepoResponse) {
    val spdx = repo.licenseSpdxId ?: return  // hide when absent
    Chip(
        leadingIcon = { Icon(Icons.License, contentDescription = null) },
        label = { Text(spdx) },
        modifier = Modifier.semantics {
            // Use the full name for accessibility narration.
            contentDescription = repo.licenseName ?: "Licensed under $spdx"
        },
        onClick = { openInBrowser("https://github.com/${repo.fullName}/blob/HEAD/LICENSE") },
    )
}
```

---

## 6. Filter / search use cases (out of scope for this PR but FYI)

`licenseSpdxId` is now indexed in Meilisearch via the `license_spdx_id` field on the search document. If you want to add "filter by license" to the search screen later, it's already there — call `/v1/search` with a Meilisearch filter expression. Not implementing that here; just noting the data is available.

Common useful filter sets:
- "Permissive only": `MIT`, `Apache-2.0`, `BSD-2-Clause`, `BSD-3-Clause`, `MPL-2.0`, `Unlicense`, `0BSD`, `ISC`.
- "Copyleft only": `GPL-2.0`, `GPL-3.0`, `AGPL-3.0`, `LGPL-2.1`, `LGPL-3.0`.
- "Permissive or copyleft (anything but proprietary)": null exclusion + filter list.

---

## 7. What you do NOT need to do

- **No separate license fetch.** Don't call `/repos/{o}/{n}/license` against GitHub or any equivalent backend route — the value is on the repo response.
- **No license-text rendering.** We don't ship the full LICENSE text in the response (it can be hundreds of lines + GitHub already does this beautifully on their site). Tap the chip to open GitHub.
- **No license validation client-side.** Don't try to verify the SPDX ID against a list — backend trusts whatever GitHub returns. New SPDX tags appear over time; whitelisting client-side would create silent breakage.

---

## 8. Acceptance criteria

- [ ] `RepoResponse` deserializes with `licenseSpdxId` + `licenseName` on every call site.
- [ ] Details screen renders a license chip when `licenseSpdxId != null`, hides cleanly otherwise.
- [ ] Chip tap opens the LICENSE file on GitHub in an external browser.
- [ ] Tooltip / accessibility label uses `licenseName` when available.
- [ ] No crash when the field is absent (older server response during rollout).

---

## 9. Authoritative reference

Backend definitions:
- `model/RepoResponse.kt` — `licenseSpdxId` + `licenseName` fields.
- `db/migration/V15__license_info.sql` — the columns.
- `ingest/GitHubSearchClient.kt` — `GitHubLicense` DTO + ingest writes.
- `routes/RepoRoutes.kt`, `routes/SearchRoutes.kt`, `db/RepoRepository.kt` — mappers.

If client and server disagree, backend wins; file an issue on the backend repo.
