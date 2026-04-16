# GitHub Store Backend

Ktor backend service for the GitHub Store app (KMP + Compose Multiplatform).

## Quick start

```bash
docker compose up -d          # Postgres + Meilisearch + app + Caddy
curl http://localhost:8080/v1/health
```

## Project structure

```
src/main/kotlin/zed/rainxch/githubstore/
├── Application.kt            # Entry point, Ktor server setup
├── Plugins.kt                # Serialization, CORS, compression, logging, error handling
├── AppModule.kt              # Koin DI module
├── routes/                   # Ktor route definitions (one file per resource)
│   ├── Routing.kt
│   ├── HealthRoutes.kt
│   └── EventRoutes.kt
├── db/                       # Database layer
│   ├── DatabaseFactory.kt    # HikariCP + Flyway + Exposed init
│   ├── Tables.kt             # Exposed table definitions
│   └── EventRepository.kt
├── model/                    # DTOs (kotlinx-serialization)
│   ├── EventRequest.kt
│   └── HealthResponse.kt
└── ingest/                   # Background worker logic (future)
```

## Tech stack

- **Ktor 3.1** — HTTP server
- **Exposed** — Kotlin SQL DSL
- **HikariCP** — Connection pool
- **Flyway** — Database migrations (src/main/resources/db/migration/)
- **Koin** — Dependency injection
- **Postgres 17** — Database
- **Meilisearch** — Search engine (wired later)
- **Caddy** — Reverse proxy + auto HTTPS

## Key conventions

- All API routes are under `/v1/`
- Environment config via env vars (see .env.example)
- Flyway migrations are numbered: V1__, V2__, etc.
- One file per route resource in routes/
- Never log raw search queries or device IDs
- Backend is optional — client falls back to GitHub direct
