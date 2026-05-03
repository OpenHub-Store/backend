# Admin Panel — Announcements Authoring

**Audience:** website coding agent.
**Goal:** add an "Announcements" tab to the existing admin panel so the maintainer can author, validate, preview, and publish in-app announcements without hand-editing JSON or running CLI tools.

---

## 1. Architectural context (read this first — it constrains the design)

The backend that serves announcements is `github-store-backend`. **It does not have a write API for announcements.** Storage is **filesystem-in-repo**: each announcement is one JSON file at `src/main/resources/announcements/<id>.json` in the backend repo. The deploy pipeline rebuilds the JAR with the new file baked in.

This is intentional. The backend spec (`docs/backend/announcements-endpoint.md` §3) chose filesystem-in-repo over a DB table for these reasons:

- Full git history of every announcement and translation.
- Translators submit PRs adding `i18n` blocks; same review flow as code.
- Restoring a deleted announcement = `git revert`.
- No admin write API to harden, no rate limit to tune, no schema migration when fields change.

**Therefore the admin panel must publish via the GitHub API**, not via a direct backend call. The flow is:

```
Admin panel form
  ↓
client-side validate (mirror server validator)
  ↓
preview (render how the app will display it)
  ↓
[Publish] button
  ↓
GitHub API: create branch, commit <id>.json, open PR against main
  ↓
maintainer reviews + merges PR in GitHub
  ↓
deploy runs (manual `./deploy.sh` or CI on merge to main)
  ↓
client picks it up on next cold start (10-min CDN TTL)
```

If at any future point the backend gains a write API, the panel can switch to direct POST. Until then, **the panel never touches the backend at runtime — only the GitHub repo at author time.**

---

## 2. Scope of this document

This document specifies:

1. The **form fields** the panel must expose.
2. The **validation rules** the panel must enforce client-side (mirror of the backend validator).
3. The **preview** the panel must render so authors see what the app will show.
4. The **publish flow** that produces a GitHub PR with the new file.
5. The **edit / delete** flows for existing announcements.
6. The **i18n authoring** flow.

Out of scope:

- The visual design of the panel (use the existing admin-panel design system).
- Authentication for the admin panel (assumed to already exist — this is added inside the existing auth boundary).
- Any direct interaction with the runtime backend.

---

## 3. Form fields

Every field below maps 1:1 to the `AnnouncementDto` in
`docs/backend/announcements-endpoint.md` §2. **Field names must be camelCase
exactly as listed** — they are serialized verbatim into the JSON file the
backend will deserialize.

### 3.1 Required fields

| Field | Input type | Constraint |
|-------|-----------|------------|
| `id` | text input | `^[a-z0-9-]{1,64}$`, non-empty, ≤ 64 chars. **Default value:** `<YYYY-MM-DD>-<auto-slug-from-title>` populated when the title is filled. Editable. |
| `publishedAt` | date+time picker | ISO 8601 UTC. Defaults to "now". Sent as e.g. `2026-06-15T00:00:00Z`. |
| `severity` | radio / segmented control | `INFO` (default) \| `IMPORTANT` \| `CRITICAL` |
| `category` | dropdown | `NEWS` (default) \| `PRIVACY` \| `SURVEY` \| `SECURITY` \| `STATUS` |
| `title` | text input | 1–80 chars. Live char count shown to author. |
| `body` | textarea | 50–600 chars. Live char count shown. **Markdown is NOT supported** — the client renders body as plain text. Newlines are preserved. |

### 3.2 Optional fields

| Field | Input type | Constraint / behaviour |
|-------|-----------|------------------------|
| `expiresAt` | date+time picker | ISO 8601 UTC. Default suggestion: `publishedAt + 90 days`. **Strongly recommended for time-bound items** (surveys, initiatives). Leave blank only for genuinely evergreen news (which the rubric says is rare). |
| `ctaUrl` | URL input | Must be `https://`. Reject `http://` and any other scheme client-side. |
| `ctaLabel` | text input | ≤ 30 chars. Only meaningful if `ctaUrl` is set. |
| `dismissible` | toggle | Default `true`. Auto-locked to `false` when `requiresAcknowledgment` is on. |
| `requiresAcknowledgment` | toggle | Default `false`. Toggling on auto-flips `dismissible` to `false` and disables the dismissible toggle. |
| `iconHint` | dropdown | (none) \| `INFO` \| `WARNING` \| `SECURITY` \| `CELEBRATION` \| `CHANGE` |
| `minVersionCode` | number input | Optional integer. The client filters items below this version code. |
| `maxVersionCode` | number input | Optional integer. Client filters items above this. |
| `platforms` | multi-select | `ANDROID`, `DESKTOP`. Empty = both. |
| `installerTypes` | multi-select | `DEFAULT`, `SHIZUKU`. Empty = both. |
| `i18n` | nested editor (see §6) | Per-locale title/body/ctaUrl/ctaLabel overrides. |

### 3.3 Field interlocks (UI must enforce these BEFORE allowing publish)

These are hard rules the backend validator will reject. Surface them as inline form errors, not as post-submit failures.

1. `category = SECURITY` ⇒ `severity` must be `IMPORTANT` or `CRITICAL`. When `category=SECURITY` is selected, disable the `INFO` option in the severity control and show a tooltip: "Security advisories must be IMPORTANT or CRITICAL."
2. `category = PRIVACY` ⇒ `requiresAcknowledgment` must be `true`. Auto-flip on selection and disable the toggle, with a tooltip: "Privacy-related notices legally require acknowledgment."
3. `requiresAcknowledgment = true` ⇒ `dismissible` must be `false`. Auto-flip + disable.
4. `ctaUrl` if present must start with `https://`. Reject other schemes inline.
5. `ctaUrl` and `ctaLabel`: if either is set, the other must also be set (or both empty). Don't ship a label with no URL or vice versa.

---

## 4. Live validation (mirror of server validator)

The panel must run the same validation rules the backend runs. If a draft fails any rule, the **Publish** button stays disabled and the failing rules are listed inline. The full rule set is:

1. `id`: non-empty, ≤ 64 chars, lowercase-kebab-case (recommended but not strictly required by backend; recommend it in the UI).
2. `publishedAt`: parseable as ISO 8601 UTC.
3. `expiresAt` if present: parseable as ISO 8601 UTC, AND must be after `publishedAt` (the spec doesn't strictly require this but the panel should — past `expiresAt` makes the announcement DOA).
4. `severity` ∈ {INFO, IMPORTANT, CRITICAL} (case-insensitive).
5. `category` ∈ {NEWS, PRIVACY, SURVEY, SECURITY, STATUS} (case-insensitive).
6. `iconHint` if present ∈ {INFO, WARNING, SECURITY, CELEBRATION, CHANGE}.
7. `title` length 1–80 per locale variant.
8. `body` length 50–600 per locale variant. The 50-char floor blocks "various improvements" filler.
9. The interlocks listed in §3.3.
10. `ctaUrl` (and per-locale `ctaUrl`) must be `https://` if present.
11. `i18n` keys must match BCP-47: regex `^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$`. Examples: `en`, `zh-CN`, `pt-BR`. Reject `en_US`, `EN-us-`, etc.
12. **Duplicate id check:** before publishing, the panel must list existing announcement files in the backend repo (via GitHub API `GET /repos/{owner}/{repo}/contents/src/main/resources/announcements`) and reject if `<id>.json` already exists, unless the author explicitly chose **Edit** mode (see §7).

If you want, you can call the validator code directly — but since it's Kotlin and the panel is web, the cleanest path is to **port the rules** into the panel's TypeScript / JavaScript as a small validator module. The list above is the complete contract.

---

## 5. Preview pane

Authors should see exactly what the app will render before they publish. Implement a side-by-side preview:

```
┌──────────────────┬──────────────────┐
│   Form           │    Preview       │
│   (fields)       │  (app mockup)    │
│                  │                  │
└──────────────────┴──────────────────┘
```

The preview is a **static mock of the in-app card** — it does NOT need to call the real app. Render:

1. **Severity colour stripe** (left edge): `INFO` neutral grey, `IMPORTANT` amber, `CRITICAL` red.
2. **Icon** per `iconHint` (use any icon set; map `INFO` → info, `WARNING` → triangle, `SECURITY` → shield, `CELEBRATION` → sparkle, `CHANGE` → refresh; null hint → no icon).
3. **Title** in semibold, 16pt-ish.
4. **Body** in regular weight, plain text (preserve newlines, do not parse as markdown).
5. **CTA button** with `ctaLabel` (or "Read more" if blank but `ctaUrl` is set), greyed if no URL.
6. **Dismiss / Acknowledge** affordance at the bottom: shows "Dismiss" if `dismissible=true && requiresAcknowledgment=false`, "I've read this" if `requiresAcknowledgment=true`, none if `dismissible=false && requiresAcknowledgment=false`.

The preview should also have a **locale switcher** dropdown (English / each i18n locale). Selecting a locale renders the title/body/cta in that locale's variant (or English defaults if a field isn't translated). This catches missing translations visually.

---

## 6. i18n editor

Localized variants are top-priority for this maintainer (the app has Chinese / Japanese / Korean users among others). The editor for `i18n` should look like this:

```
┌─ i18n ─────────────────────────────────┐
│ [+ Add locale ▼]                       │
│                                        │
│ ▼ zh-CN                          [×]  │
│   Title:    [_______________________]  │
│   Body:     [_______________________]  │
│   CTA URL:  [_______________________]  │
│   CTA Label:[_______________________]  │
│                                        │
│ ▼ ja                             [×]  │
│   ...                                 │
└────────────────────────────────────────┘
```

Behaviour:

- **Add locale dropdown** offers a curated list of likely locales: `zh-CN`, `zh-TW`, `ja`, `ko`, `ru`, `de`, `fr`, `es`, `pt-BR`, `it`, `tr`, `vi`, `id`, `th`, `ar`, `hi`. Plus a "Custom..." option that accepts any BCP-47 string (validated by the regex in §4).
- Each locale block has the same length / `https://` rules as the top-level fields.
- Untranslated fields fall back to English silently (this is what the client does too, so the preview should mirror it).
- Removing a locale is one click on the `[×]`.
- The editor must NOT force translators to fill in every field; partial translations are valid.

---

## 7. Edit / delete flows

The admin panel should also list **existing** announcements and offer Edit / Delete.

### 7.1 Listing

On panel load, fetch the current list via GitHub API:

```
GET /repos/{owner}/{repo}/contents/src/main/resources/announcements?ref=main
```

For each `.json` entry, fetch and decode the file (the API returns `base64`-encoded content). Render a table:

| ID | Severity | Category | Published | Expires | Title | Status |
|----|----------|----------|-----------|---------|-------|--------|
| ... | ... | ... | ... | ... | ... | active / expired / future |

"Status":

- **future**: `publishedAt > now` (won't show in app yet).
- **active**: `publishedAt <= now < expiresAt` (or no expiry).
- **expired**: `expiresAt < now` (still in repo but filtered out by backend at serve time).

Provide a filter / search by id, category, severity.

### 7.2 Edit mode

Clicking **Edit** on a row pre-fills the form with the existing JSON. The publish flow then **updates** that file (same path, new content) instead of creating a new one. PR title: `Update announcement <id>` (vs `Add announcement <id>` for new).

### 7.3 Delete mode

Clicking **Delete** opens a confirmation dialog: "Delete announcement `<id>`?" On confirm, the panel commits a deletion of the file via GitHub API and opens a PR titled `Remove announcement <id>`. **Do not hard-delete via direct push to main** — keep everything PR-gated.

The author should also be told: "If you just want this to stop showing in the app immediately, set `expiresAt` to now and **Update** instead of **Delete**. Deleting requires a PR merge + deploy. Editing the expiry takes effect on the next CDN refresh (~10 min) without needing to delete the history."

### 7.4 Expire shortcut

For convenience, add an **Expire now** button on each row that's `active` or `future`. It opens the same flow as Edit but pre-sets `expiresAt = now` and labels the PR `Expire announcement <id>`.

---

## 8. Publish flow (detailed)

When the author clicks **Publish**:

1. Run final client-side validation (§4). Block on any failure.
2. Serialize the form state to JSON. **Order fields canonically** (id, publishedAt, expiresAt, severity, category, title, body, ctaUrl, ctaLabel, dismissible, requiresAcknowledgment, minVersionCode, maxVersionCode, platforms, installerTypes, iconHint, i18n) so diffs are stable across edits. Pretty-print with 2-space indent — diffs in PRs should be readable.
3. Compute the target path: `src/main/resources/announcements/<id>.json`.
4. **Branch name:** `announcements/<id>` (replace any non-`a-z0-9-` chars). For Update mode reuse the same branch name; the GitHub API will fast-forward.
5. **Create-or-update file** via GitHub API (`PUT /repos/{owner}/{repo}/contents/{path}`). Required fields: `message`, `content` (base64 of the JSON), `branch`, and `sha` if updating an existing file.
6. **Open PR** (`POST /repos/{owner}/{repo}/pulls`) with title:
   - new: `Add announcement <id>`
   - update: `Update announcement <id>`
   - delete: `Remove announcement <id>`
   - expire: `Expire announcement <id>`
   Body: a short summary including category, severity, expiry, locales translated.
7. Show the author the PR URL with a **"View PR"** link. Optionally an **"Auto-merge"** button if the panel auth has permission (skip this for now — manual review is healthy).
8. Surface a status banner: "PR opened: review and merge in GitHub. Deploy will pick it up on next backend deploy."

### 8.1 GitHub API auth

Two viable auth strategies:

**Option A — GitHub App (preferred, long-term).**
- Create a GitHub App with `Contents: write` and `Pull requests: write` permission on the backend repo only.
- Install it on the maintainer's account / org.
- The admin panel's server-side proxy holds the private key and signs JWTs; the panel never sees the key.
- Pros: scoped to one repo, revocable, no human-account expiration.
- Cons: more setup; needs server-side handling.

**Option B — Personal Access Token (faster to ship).**
- The maintainer creates a fine-grained PAT scoped to the backend repo with `Contents: write` and `Pull requests: write`.
- The panel stores the PAT as a server-side secret (NEVER in the browser; the panel must proxy GitHub API calls through its backend).
- Pros: trivial setup.
- Cons: tied to a human account; expires; can be over-scoped if not careful.

**Pick whichever the rest of the admin panel already uses.** If the panel already has GitHub App auth for something else, extend it. If not, start with PAT and migrate later.

### 8.2 Repo coordinates

The maintainer will provide the actual `owner/repo` for the backend. Don't hardcode — read from a config endpoint or environment variable. Likely values: `<owner>/github-store-backend`.

### 8.3 Conflict handling

If the GitHub API returns 409 (file already exists in Add mode, or sha mismatch in Update mode), surface a clear error: "Another change has been merged since you opened this draft. Re-fetch the latest version and reapply your edits."

### 8.4 Network / auth failures

Any API failure must NOT silently lose the author's draft. Keep the draft in component state (and ideally in `localStorage`) so a retry doesn't require re-typing.

---

## 9. Validation against the backend's CLI

For paranoid authors / CI, the backend repo ships a Gradle task:

```
./gradlew validateAnnouncements
```

It runs the same validator the runtime uses, against every file in `src/main/resources/announcements/`. The PR opened by the admin panel will be linted by this task in CI (if CI is set up — that's a separate task for the backend repo, but the panel can rely on the validator existing locally).

The admin panel doesn't need to invoke this CLI; the panel's client-side validator is a port of the same rules. But mention the CLI in the PR body so reviewers know how to verify locally:

```
To validate this announcement locally:

  ./gradlew validateAnnouncements

Exits 0 if all files in announcements/ pass the schema.
```

---

## 10. Authoring rubric (surface this in the panel UI)

Before the **Publish** button activates, show this checklist with checkboxes the author must tick:

```
[ ] Is this worth interrupting users? If no, the website news page is a better fit.
[ ] Could a user reasonably react to this? If there's no user value or action, don't ship.
[ ] Is the body editable down to ≤ 600 chars? If not, link out via ctaUrl.
[ ] Is the severity matched to actual user impact? Default INFO. Use CRITICAL only when data, security, or app function is at risk.
[ ] Is acknowledgment legally required (privacy policy change, ToS update)?
    If yes: requiresAcknowledgment=true and dismissible=false.
[ ] Have you set expiresAt? Time-bound items must expire; evergreen news rarely belongs in this channel.
```

These are author-discipline checks, not backend rules — the panel can require all six before enabling **Publish**, or just show them as guidance. Maintainer's call.

**Cadence target:** ≤ 1 non-security item per month. The panel could surface a soft warning if a new announcement is being published within 30 days of the previous non-security one, with the message: "You shipped an announcement on `<date>`. More than ~one per month and users learn to dismiss reflexively."

---

## 11. Out of scope (explicit non-goals)

- **Push notifications.** The announcements feed is in-app only.
- **A/B variant delivery.** Every user sees the same payload.
- **Per-user audience targeting.** No user identifier crosses the wire.
- **Direct push to backend runtime.** All publishes go through the GitHub PR + deploy cycle. There is no backend write endpoint, and adding one is a separate spec.
- **Aggregated impression telemetry.** No "X% of users have read this" — the backend has no view of user state.

If any of these are eventually wanted, they are separate features with separate privacy implications and need their own spec.

---

## 12. Acceptance criteria

The admin panel feature is done when:

- [ ] **Authoring:** maintainer can open the Announcements tab, fill in all required fields, see live validation, see a side-by-side preview, add i18n variants, and click Publish.
- [ ] **Publishing:** clicking Publish opens a PR in the backend repo with a correctly-named branch, a canonically-formatted JSON file, and a clear PR title / body. The PR link is shown back to the maintainer.
- [ ] **Listing:** existing announcements load from the repo and are shown with status (future / active / expired), filterable.
- [ ] **Editing:** clicking Edit on an existing item pre-fills the form and produces an Update PR, not a new file.
- [ ] **Deleting / expiring:** Delete and Expire-now buttons each produce the appropriate PR.
- [ ] **i18n:** maintainer can add a locale, fill its variants, and see the preview switch to that locale.
- [ ] **Field interlocks:** all five interlocks in §3.3 are enforced inline (UI auto-flips toggles and disables conflicting controls).
- [ ] **Validator parity:** every rule in §4 fails the form before Publish becomes clickable.
- [ ] **No backend coupling:** the panel makes zero requests to the backend (`api.github-store.org`). All requests go to GitHub's API.
- [ ] **Auth:** GitHub credentials live server-side; the browser never sees a PAT or app private key.

When all twelve are checked, the panel is shipped. The first real announcement after the privacy-policy update should be authored end-to-end through the panel as a smoke test.

---

## 13. Reference: full backend contract

The authoritative source for the JSON shape and validation rules is in the backend repo:

```
docs/backend/announcements-endpoint.md
```

If that doc and this one ever disagree, **the backend doc wins** — it's the contract the runtime enforces. This panel doc is downstream guidance.

---

## 14. Deployment prerequisites (must hold before the panel ships)

The admin panel's "PR-gated" guarantee is **only** as strong as the backend repo's GitHub configuration. Before flipping the panel on for end users, the maintainer must verify the following on `<owner>/github-store-backend`:

### 14.1 Branch protection on `main`

Required settings on the `main` branch ruleset:

- **Require a pull request before merging.** Direct pushes to `main` blocked for everyone, including the maintainer (admins can still bypass in emergencies — that's fine, but it must not be the default path).
- **Require approvals** ≥ 1, OR (for solo-maintainer setups) **require status checks to pass** before merge.
- **Restrict who can push to matching branches** so a leaked PAT scoped to `Contents: write` cannot push directly to `main`.
- **Block force pushes** and **block deletions**.
- **Do not allow** "Allow specified actors to bypass required pull requests" unless absolutely necessary; if present, it must NOT include the GitHub App / PAT identity used by the admin panel.

Without `Require a pull request before merging`, a leaked or over-scoped credential used by the panel could push `<id>.json` directly to `main`, bypassing every review the rest of this doc assumes happens. The PR flow becomes cosmetic.

### 14.2 CODEOWNERS gate on the announcements path

Add to `<owner>/github-store-backend/.github/CODEOWNERS` (create the file if it doesn't exist):

```
# Announcements are user-facing trusted content. Any change to
# src/main/resources/announcements/ must be reviewed by the maintainer
# personally — not auto-approved by another contributor or by the panel
# itself acting on a translator's behalf.
src/main/resources/announcements/  @<maintainer-github-handle>
```

Then enable **Require review from Code Owners** on the `main` branch ruleset.

This means: even if a translator opens a PR to add a `zh-CN` block, the maintainer's approval is required before merge. For the panel-authored case (where the maintainer is opening the PR themselves), an org-wide policy may need adjustment — see §14.4.

### 14.3 Credential scoping (recap from §8.1)

- If using a **GitHub App**: scope = backend repo only; permissions = `Contents: write` + `Pull requests: write`. Nothing else. No `Workflows: write`, no `Administration: write`.
- If using a **fine-grained PAT**: same scope. Generated by the maintainer's account, stored server-side only, NEVER in the browser. Set an expiration (90 days max) and rotate.

The credential must NOT be authorized to bypass branch protection. Verify by attempting (manually, once, in a controlled test) to push directly to `main` with the credential — it should be **rejected by the API**. If it succeeds, branch protection is misconfigured and the panel is not safe to ship.

### 14.4 Solo-maintainer caveat

If `<owner>/github-store-backend` is a single-maintainer repo, **GitHub's "require approvals" cannot be satisfied by the same account that opened the PR** — by default, a PR author cannot self-approve. This is a feature, not a bug, but it means the maintainer's panel-published PR will sit waiting for an approval that no other reviewer exists to give.

Two acceptable resolutions:

1. **Self-merge after CI passes.** Drop the "require approvals" requirement and rely on **required status checks** instead (specifically: a CI job that runs `./gradlew validateAnnouncements` and `./gradlew test`). The CI is the gate; the maintainer-as-PR-author still has to wait for it to pass before clicking merge in the GitHub UI. The panel cannot bypass this because the panel's credential is scoped only to write content + open PRs, not to merge.
2. **Add a second review identity.** Either a co-maintainer, or a "review bot" GitHub App that approves on green CI. More moving parts; only worth it for higher-touch repos.

Default recommendation: **Option 1.** It preserves the spirit of "PR-gated" (CI enforces the validator gate before content reaches `main`) without manufacturing fake reviewers.

### 14.5 Acceptance verification

Before announcing the panel is "ready for production use":

- [ ] Open a PR from a clean branch directly via GitHub UI (no panel involvement). Confirm it is blocked from merge until either (a) an approval is recorded, or (b) the required CI status check passes — depending on §14.4 choice.
- [ ] Attempt a direct `git push origin main` with the panel's credential. Confirm it is rejected with a `protected branch` error.
- [ ] Push a commit to a feature branch via the panel credential. Confirm it succeeds and that opening a PR succeeds.
- [ ] Merge a panel-authored PR end-to-end. Confirm the deployed backend serves the new file after `./deploy.sh` runs.

Until all four checkboxes pass, **the panel is not safe to enable for production publishing** — even if the UI works perfectly. The backend can't tell the difference between a reviewed merge and a forced push, so the only enforcement of the review invariant is at the GitHub layer described above.
