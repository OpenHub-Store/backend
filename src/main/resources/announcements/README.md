# Announcements

In-app announcements served by `GET /v1/announcements`. Each `<id>.json` file
in this directory is one `AnnouncementDto`. The endpoint glues them into the
response envelope at serve time.

## Authoring an announcement

1. Add `<id>.json` here. ID convention: `<YYYY-MM-DD>-<kebab-case-slug>`.
2. Validate against the rubric in
   `docs/backend/announcements-endpoint.md` §2 / §4.
3. Open a PR. Translators add `i18n` blocks to the same file.
4. Deploy applies it -- there is no in-place hot reload.

## Required fields

`id`, `publishedAt` (ISO 8601 UTC), `severity` (`INFO` | `IMPORTANT` |
`CRITICAL`), `category` (`NEWS` | `PRIVACY` | `SURVEY` | `SECURITY` |
`STATUS`), `title` (≤80 chars), `body` (50–600 chars).

## Cross-rules (enforced server-side)

- `category=PRIVACY` ⇒ `requiresAcknowledgment=true`.
- `category=SECURITY` ⇒ `severity` ∈ {IMPORTANT, CRITICAL}.
- `requiresAcknowledgment=true` ⇒ `dismissible=false`.
- `ctaUrl` must be `https://`.
- Duplicate `id` across files rejects the entire payload.

## Cadence

Target: ≤1 non-security item per month. More than that and users learn to
dismiss reflexively, which kills credibility for the next real announcement.
