# GitHub Store · Backend

<p>
  <img src="https://api.github-store.org/v1/badge/static/8/2?label=Kotlin&icon=code" alt="Kotlin"/>
  <img src="https://api.github-store.org/v1/badge/static/12/2?label=Ktor%203&icon=bolt" alt="Ktor 3"/>
  <img src="https://api.github-store.org/v1/badge/static/5/2?label=Postgres&icon=package" alt="Postgres"/>
  <img src="https://api.github-store.org/v1/badge/static/3/2?label=Meilisearch&icon=star" alt="Meilisearch"/>
  <img src="https://api.github-store.org/v1/badge/static/11/2?label=Apache--2.0&icon=palette" alt="Apache-2.0"/>
</p>

Backend for **[GitHub Store](https://github.com/OpenHub-Store/GitHub-Store)** — a cross-platform FOSS app store for Android and Desktop.

> Optional by design. The client falls back to static data when this service is down.

```bash
curl https://api.github-store.org/v1/health
# {"status":"healthy","postgres":"ok","meilisearch":"ok"}
```

---

## Features

- **Search** that blends a fast local index with a live GitHub fallback when results come up short.
- **Smart ranking** that mixes star count with real behavioral signals — clicks, installs, release freshness — so results improve as people use the app.
- **Bring-your-own GitHub token.** Each authenticated user contributes their own 5,000/hr quota; anonymous traffic shares a small rotation pool.
- **Stateless OAuth proxy** for GitHub's device-flow login. Access tokens pass through; nothing is logged, cached, or persisted.
- **M3-styled badges** rendered server-side in 12 hues × 3 shades. Drop-in replacement for shields.io with a Material You aesthetic.
- **CDN + direct origin.** Two public hostnames so users on networks that flag shared CDN IPs still have a path through.
- **Aggressive caching** at the browser, CDN, and origin layers — most requests never touch the database.
- **Privacy-first telemetry.** Device IDs are hashed with a server-side pepper. Raw search queries are never stored.

---

## Badges

Drop these into any README:

```html
<img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/release/9/1"/>
<img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/stars/3/1"/>
<img src="https://api.github-store.org/v1/badge/OpenHub-Store/GitHub-Store/downloads/5/2"/>
<img src="https://api.github-store.org/v1/badge/static/10/1?label=Kotlin&icon=code"/>
```

**Format**

```
/v1/badge/{owner}/{name}/{kind}/{style}/{variant}    per-repo: release · stars · downloads
/v1/badge/{kind}/{style}/{variant}                   global:   users · fdroid
/v1/badge/static/{style}/{variant}?label=…&icon=…    custom label + icon
```

**Style** `1` red · `2` orange · `3` yellow · `4` lime · `5` green · `6` teal · `7` indigo · `8` blue · `9` purple · `10` pink · `11` magenta · `12` neutral.
**Variant** `1` dark · `2` medium · `3` light.

Palette mirrors [ziadOUA/m3-Markdown-Badges](https://github.com/ziadOUA/m3-Markdown-Badges) hex-for-hex.

---

## API

Everything lives under `/v1/`.

| Endpoint | What it does |
|---|---|
| `GET /health` | Postgres + Meilisearch status |
| `GET /search?q=` | Local index with GitHub passthrough on sparse results |
| `GET /search/explore?q=&page=` | Deep paginated GitHub search |
| `GET /categories/{cat}/{platform}` | Pre-ranked lists |
| `GET /topics/{bucket}/{platform}` | Topic-bucketed repos |
| `GET /repo/{owner}/{name}` | Repo detail |
| `GET /readme/{owner}/{name}` | Cached README proxy |
| `GET /user/{username}` | Cached user proxy |
| `POST /events` | Opt-in telemetry |
| `POST /auth/device/{start,poll}` | Stateless OAuth device-flow proxy |
| `GET /badge/…` | M3 SVG badges |

---

## Run locally

```bash
docker compose up postgres meilisearch -d
./gradlew buildFatJar
DATABASE_URL=jdbc:postgresql://localhost:5432/githubstore \
  DATABASE_PASSWORD=githubstore \
  MEILI_URL=http://localhost:7700 \
  MEILI_MASTER_KEY=devkey \
  GITHUB_OAUTH_CLIENT_ID=<your-oauth-client-id> \
  DEVICE_ID_PEPPER=$(openssl rand -hex 32) \
  java -jar build/libs/github-store-backend.jar
```

Open `http://localhost:8080/v1/health`.

---

## Deploy

Single VPS, all-in-one Docker Compose. Caddy terminates TLS for both hostnames; the app runs alongside Postgres and Meilisearch.

```bash
./deploy.sh <your-server-ip>
```

---

## Stack

Kotlin · Ktor 3 · Exposed · Koin · Postgres 17 · Meilisearch · Caddy · Sentry.

---

## License

Apache-2.0. Badge palette from [ziadOUA/m3-Markdown-Badges](https://github.com/ziadOUA/m3-Markdown-Badges). Icons from [Material Symbols](https://fonts.google.com/icons).
