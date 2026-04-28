# 1. Three event-shaped pipelines

Status: Accepted

## Context

The backend has accumulated three pipelines that all look superficially like
"a client posts a small JSON record, we persist it":

- **`/v1/events` (E1+) — behavioral signals.** Identifiable per-device rows that
  drive the ranking pipeline (`SignalAggregationWorker` reads them, writes
  `RepoSignals`, and pushes `search_score` into Meilisearch). Code:
  `routes/EventRoutes.kt`, `db/EventRepository.kt`, table `events`.
  Privacy posture: `device_id` is hashed via `util/PrivacyHash.kt` before
  insert, but the rows are designed to live forever and feed ML.
- **`/v1/telemetry` (E6) — product metrics.** Ephemeral counters and gauges
  for funnel/health analysis. No ranking impact, no per-device retention beyond
  short-window aggregation. Different table (`telemetry_events`), different
  retention, different privacy contract.
- **Search misses — query observability.** Privacy-hashed query records
  (`sha256(canonicalize(q))`, 8-char prefix exposed via `/v1/internal/metrics`).
  No raw text, no device association. The V6 migration explicitly dropped the
  legacy `query_sample` column. See the privacy-hashing note in `CLAUDE.md`.

These pipelines have been informally treated as "the events thing", which has
caused at least one near-miss where a telemetry-shaped field was nearly added
to `events` (and therefore to the ML pipeline + the long-retention table).

## Decision

Keep the three pipelines separate. Do not unify them into one table or one
endpoint. The privacy postures genuinely differ — collapsing them would either
relax search-miss privacy or impose ranking-grade hashing on telemetry.

## Consequences

Every new event type must be classified up-front. The routing rule:

| Shape | Pipeline |
| --- | --- |
| Identifiable + drives ranking | `/v1/events` |
| Ephemeral + product metric | `/v1/telemetry` |
| Query-shaped | search misses |

Reviewers blocking a PR that adds a field to the wrong pipeline should
reference this ADR. New pipelines (a fourth shape) require a new ADR — do not
silently extend one of the existing three.

See `CLAUDE.md` for the architectural data-flow diagram and the per-pipeline
privacy notes (`Privacy hashing`, `Persistence is off the request path`).
