# Security Policy

Thanks for taking the time to look. This project is a small, free, optional backend for an open-source app store. We take vulnerability reports seriously and would rather hear from you privately than read about it on Twitter.

---

## Reporting a vulnerability

Please email **`security@github-store.org`** with:

- a description of the issue,
- steps to reproduce (a curl one-liner is great),
- the affected endpoint(s) or commit hash,
- any logs, payloads, or proof-of-concept you have,
- whether you're OK being acknowledged publicly when this is fixed (see "Hall of Fame" below).

If you want to send the report encrypted, we'll publish a PGP key alongside this file once we've generated and rotated one. Until then, plain email is fine — please just don't include working exploit payloads in clear text where you don't have to.

Please **do not** open public GitHub issues for security problems. Issues are world-readable from the moment you click submit.

---

## Triage

- We aim to **acknowledge your report within 72 hours** (usually faster on weekdays).
- We'll confirm whether we can reproduce, share our initial severity assessment, and tell you what we plan to do.
- If we disagree about whether something is a vulnerability, we'll explain why rather than ghost you.

This is a one-person operation, so timezones and life happen. If you don't hear back in 72 hours, a polite nudge to the same address is welcome.

---

## Severity guidance

These are the levels we use internally so you know what we're treating your report as. Plain-English version:

| Level | Looks like                                                                                       | Target time-to-fix |
|-------|--------------------------------------------------------------------------------------------------|--------------------|
| P0    | Token or secret leak. RCE on the server. Auth-proxy bypass that exposes a user's GitHub token. Anything that lets an attacker impersonate users at scale. | < 24 hours |
| P1    | Rate-limit bypass. Ability to exhaust the GitHub token-rotation pool. Persisted XSS in any HTML we serve (badges, dashboards). PII leak between users. | < 7 days |
| P2    | Information disclosure with low impact (e.g., timing oracle on a hash). Cache poisoning that affects only the reporter's session. Issues that require an authenticated and unrealistic precondition. | < 30 days |
| P3 / informational | Best-practice gaps (missing security header, weaker-than-ideal default), defense-in-depth suggestions. | best-effort |

Don't worry about getting the level right yourself — describe the impact and we'll classify it.

---

## Disclosure timeline

Default is **coordinated disclosure with a 90-day window** from acknowledgment. If we fix it earlier, we'll typically publish a brief advisory and credit you (with permission) shortly after the deploy.

If 90 days isn't enough — for example, the fix requires a coordinated upstream change — we're happy to extend, just ask. Conversely, if we're sitting on a fix and you'd rather we publish sooner, ask.

If you feel a report is being ignored or mishandled and the timeline is running out, you can escalate to `abuse@github-store.org` or publish on your own schedule. We'd prefer that not happen, but you're not contractually bound to silence by reporting.

---

## Hall of Fame

We don't have a paid bug-bounty program. We do publicly thank reporters in [`THANKS.md`](./THANKS.md) once a fix is shipped, with the level of detail you're comfortable with (full name, handle, anonymous, whichever). Tell us your preference in the report.

---

## What's NOT in scope

The following are not in scope here, and reports about them won't be treated as vulnerabilities against this project. They may still be worth reporting — just to the right party.

- **GitHub's API.** Report to GitHub Security.
- **Hetzner's infrastructure** (the VPS, network, hypervisor). Report to Hetzner.
- **F-Droid** (referenced as a download mirror by the client). Report to F-Droid.
- **Sentry, Stripe, Google Play, Apple App Store, Gcore, Let's Encrypt, FCM, APNs.** Report to the respective vendor.
- **Vulnerabilities in the GitHub Store client app itself.** That repo is at `https://github.com/<...>/GitHub-Store` — please report there. Issues that span both (e.g., a backend response that triggers a client bug) are fine to report here, mention the client component.
- **Vulnerabilities in third-party dependencies that affect us only theoretically** (no exploitable path through our code). Report to the upstream project; we'll pick up the fix when we update.
- **Self-XSS, missing CSRF on stateless endpoints, missing rate limit on a documented public endpoint that's already rate-limited.** These are usually scanner false positives and we'll close them quickly.
- **DMARC / SPF / DKIM / TLS configuration suggestions** unless they actually enable an attack. We'll take the suggestion as informational, but it isn't a vulnerability.

---

## Safe harbor

We won't take legal action against good-faith security research conducted under these rules:

- You make a reasonable effort to avoid privacy violations of other users.
- You do not run denial-of-service attacks (volumetric, resource-exhaustion, or otherwise) against the service. Functional testing of rate limits at the documented thresholds is fine.
- You do not exploit the issue beyond what's needed to demonstrate it. For example: prove you can read another user's data by reading *your own* test account, not by reading a real user's. If a proof requires touching real user data, stop and tell us — we'll set up a way to demonstrate it together.
- You do not modify, delete, or exfiltrate data that isn't yours.
- You give us a reasonable chance to fix the issue before public disclosure (see "Disclosure timeline").
- You are not on a sanctions list and your research does not violate the laws of your jurisdiction.

If you're unsure whether something you want to test is in bounds, email `security@github-store.org` first. We'd rather scope it with you than have you guess.

---

## Contact

- Vulnerability reports: `security@github-store.org`
- Operational abuse / takedowns: `abuse@github-store.org`
- Privacy / data requests: `privacy@github-store.org`

Last updated: 2026-04-25
