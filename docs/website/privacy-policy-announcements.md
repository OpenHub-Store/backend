# Privacy Policy Update — Announcements Feed

**Audience:** website coding agent.
**Goal:** add a paragraph to the GitHub Store privacy policy disclosing the in-app announcements feed.

---

## 1. Why this update is needed

The GitHub Store app (Android + Desktop) now ships an in-app **Announcements** feature.

On every cold start the client makes a single HTTPS request to:

```
GET https://api.github-store.org/v1/announcements
```

This is a public, anonymous, byte-identical feed of in-app notices (privacy-policy change notices, surveys, security advisories, occasional initiative endorsements). The privacy policy must disclose it because:

1. The app makes a network request to a server we operate.
2. Standard HTTP access logs (timestamp, IP, status, response size) are recorded for ~7 days.
3. Users have a right to know what data leaves their device.

The privacy story is intentionally minimal:

- The endpoint receives **no user identifier** (no device ID, no account, no cookie).
- The endpoint returns the **same payload to everyone** — no per-user, per-IP, or per-region variation.
- Whether the user has read or dismissed an announcement is recorded **only on the device** — never sent back.
- Standard access logs are retained for **7 days**, matching the rest of the backend.

---

## 2. Drop-in paragraph

Add the following paragraph to the privacy policy. Suggested location: under the existing "Data we collect" or "What the app sends to our servers" section. Adjust phrasing to match the policy's voice (formal vs casual), but **do not** weaken the privacy claims — they are technical guarantees enforced server-side.

> **Announcements feed.** GitHub Store fetches a public, anonymous feed at `https://api.github-store.org/v1/announcements` on launch. The endpoint receives no user identifier and returns the same payload to every caller. Whether you have read or dismissed an individual announcement is recorded only on your device; we do not record this server-side.

---

## 3. Integration notes

When you place the paragraph:

- **Section grouping.** It fits alongside any existing disclosure of network calls the app makes (e.g. search, GitHub passthrough, badge fetches). If those are listed, add Announcements to the same list and use the same paragraph style.
- **Heading.** If the existing structure uses `###` subheadings per request type (recommended pattern), add `### Announcements feed` and place the paragraph below.
- **Cross-reference.** If the policy has a "Logs and retention" section that already states a 7-day retention window, you can omit the retention sentence from the paragraph and rely on the cross-reference. If no such section exists, add the retention sentence:

  > Standard server access logs (timestamp, IP, response status, response size) are retained for 7 days, the same as the rest of our infrastructure.

- **Wording flexibility.** "Anonymous" can be replaced with "non-personalised" or "uniform" if the policy avoids the word "anonymous" elsewhere. The technical claim is what matters: the endpoint returns the **same bytes** to every caller in the same locale window, and **does not consult** any client-identifying header.

---

## 4. What NOT to write

Avoid the following phrasings — they over-promise or mislead:

- ❌ "We do not log anything." Standard access logs exist (timestamp, IP, status, size) — same as the rest of the API. Don't claim otherwise.
- ❌ "We collect anonymous usage data." We don't. The feed has no usage telemetry; impressions are not tracked server-side.
- ❌ "Announcements are personalised based on your usage." They aren't. The same payload goes to everyone; the **client** filters by app version / platform locally.
- ❌ "You can opt out of announcements." There is no opt-out flag on the request itself (the request is unconditional on cold start), but users can mute categories in the app's inbox UI. If you describe an opt-out, describe it accurately as **client-side category muting**, not a server-side opt-out.

---

## 5. Where the privacy promise is enforced (for your reference, not the policy text)

These are technical facts that back the policy text. You don't need to put them in the policy, but they explain why the language above is justifiable:

- The backend route handler reads no client headers beyond what the access log already records. No `X-GitHub-Token`, no `Authorization`, no `Cookie`, no custom analytics header.
- Sentry's `beforeSend` hook strips `Authorization`, `X-GitHub-Token`, `X-Admin-Token`, `Cookie`, `Set-Cookie`, `X-Forwarded-For`, and `CF-Connecting-IP` before any error event leaves the process.
- The response body is byte-identical for every caller; there is no per-IP or per-region branching in the handler.
- No DB row, in-memory map, or log line is keyed to a specific request beyond the standard access log.

If a future change weakens any of those guarantees, the privacy paragraph **must** be updated in lockstep.

---

## 6. Acceptance check

Before merging the policy update, confirm:

- [ ] The paragraph above (or your re-voiced version) appears in the published policy.
- [ ] No new claim was introduced that the backend doesn't actually deliver (e.g. "we never log IPs" — we do, for 7 days, same as the rest of the infrastructure).
- [ ] If the policy has a versioned changelog, an entry is added describing this change.
- [ ] If the policy has an "effective date", it is bumped to today.
- [ ] The published page validates (no broken links, no missing anchors) and the change ships behind whatever review process the website normally uses.

That's all that's needed. The backend already serves the endpoint and enforces every guarantee the paragraph claims.
