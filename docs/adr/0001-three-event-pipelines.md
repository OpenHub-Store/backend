# 1. Two event-shaped pipelines

Status: Accepted

> **Update (2026-04-28):** the original ADR documented three pipelines including
> a `/v1/telemetry/events` product-metrics path. That pipeline was reverted in
> the same week it shipped; the surface, schema (`telemetry_events`), code, and
> rate-limit bucket were removed and the table dropped via migration V13. This
> ADR is preserved as the canonical "do not unify" decision but the live system
> now has two pipelines, not three.

## Context

The backend has two pipelines that look superficially like "a client posts a
small JSON record, we persist it":

- **`/v1/events` (E1+) — behavioral signals.** Identifiable per-device rows that
  drive the ranking pipeline (`SignalAggregationWorker` reads them, writes
  `RepoSignals`, and pushes `search_score` into Meilisearch). Code:
  `routes/EventRoutes.kt`, `db/EventRepository.kt`, table `events`.
  Privacy posture: `device_id` is hashed via `util/PrivacyHash.kt` (HMAC-SHA256
  with the per-environment pepper) before insert. Rows are retained for 90 days
  (see `RetentionWorker`); aggregated signals in `RepoSignals` and
  `RepoStatsDaily` are kept indefinitely.
- **Search misses — query observability.** Privacy-hashed query records
  (`sha256(canonicalize(q))`, 16-char prefix exposed via `/v1/internal/metrics`).
  No raw text, no device association. The V6 migration explicitly dropped the
  legacy `query_sample` column. See the privacy-hashing note in `CLAUDE.md`.

These pipelines have been informally treated as "the events thing", which has
caused at least one near-miss where a query-shaped field was nearly added
to `events` (and therefore to the ML pipeline + the long-retention table).

## Decision

Keep the two pipelines separate. Do not unify them into one table or one
endpoint. The privacy postures genuinely differ — collapsing them would either
relax search-miss privacy or impose ranking-grade hashing on observability data.

## Consequences

Every new event type must be classified up-front. The routing rule:

| Shape | Pipeline |
| --- | --- |
| Identifiable + drives ranking | `/v1/events` |
| Query-shaped | search misses |

Reviewers blocking a PR that adds a field to the wrong pipeline should
reference this ADR. New pipelines (a third shape) require a new ADR — do not
silently extend one of the existing two. The previous attempt at a third
pipeline (`/v1/telemetry/events` for product metrics) was reverted because the
client-side consent UI never shipped and the disclosure work outweighed the
operator value.

See `CLAUDE.md` for the architectural data-flow diagram and the per-pipeline
privacy notes (`Privacy hashing`, `Persistence is off the request path`).
