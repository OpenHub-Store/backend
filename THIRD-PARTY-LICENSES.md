# Third-Party Licenses

This file lists the third-party software and assets the GitHub Store backend depends on, along with their licenses. It is maintained by hand for now. The plan is to replace it with output from the `com.github.jk1.dependency-license-report` Gradle plugin once that's wired into the build, so this file becomes auto-regenerated on every release. Until then, treat this list as canonical and update it whenever `build.gradle.kts` changes.

Versions reflect the values in `build.gradle.kts` at the last-updated date below. Transitive dependencies are not enumerated here individually — they follow the licenses of their direct parents and are surfaced by the eventual auto-generated report. If you need a transitive-aware bill of materials in the meantime, run `./gradlew dependencies` against the `runtimeClasspath` configuration.

---

## Apache License 2.0 (`Apache-2.0`)

| Name                                       | Version   | Source                                                            |
|--------------------------------------------|-----------|-------------------------------------------------------------------|
| Kotlin standard library                    | 2.1.20    | https://github.com/JetBrains/kotlin                               |
| `kotlin("plugin.serialization")`           | 2.1.20    | https://github.com/Kotlin/kotlinx.serialization                   |
| `io.ktor:ktor-server-core`                 | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-netty`                | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-content-negotiation`  | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-status-pages`         | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-call-logging`         | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-cors`                 | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-default-headers`      | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-compression`          | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-rate-limit`           | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-auth`                 | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-auto-head-response`   | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-client-core`                 | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-client-cio`                  | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-client-content-negotiation`  | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-serialization-kotlinx-json`  | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `io.ktor:ktor-server-test-host`            | 3.1.2     | https://github.com/ktorio/ktor                                    |
| `org.jetbrains.exposed:exposed-core`       | 0.60.0    | https://github.com/JetBrains/Exposed                              |
| `org.jetbrains.exposed:exposed-dao`        | 0.60.0    | https://github.com/JetBrains/Exposed                              |
| `org.jetbrains.exposed:exposed-jdbc`       | 0.60.0    | https://github.com/JetBrains/Exposed                              |
| `org.jetbrains.exposed:exposed-kotlin-datetime` | 0.60.0 | https://github.com/JetBrains/Exposed                             |
| `org.jetbrains.exposed:exposed-json`       | 0.60.0    | https://github.com/JetBrains/Exposed                              |
| `com.zaxxer:HikariCP`                      | 6.3.0     | https://github.com/brettwooldridge/HikariCP                       |
| `io.insert-koin:koin-ktor`                 | 4.0.4     | https://github.com/InsertKoinIO/koin                              |
| `io.insert-koin:koin-logger-slf4j`         | 4.0.4     | https://github.com/InsertKoinIO/koin                              |
| `io.sentry:sentry`                         | 8.13.2    | https://github.com/getsentry/sentry-java                          |
| `io.sentry:sentry-logback`                 | 8.13.2    | https://github.com/getsentry/sentry-java                          |
| Meilisearch (server, runtime dependency via Docker Compose) | per release | https://github.com/meilisearch/meilisearch |
| Caddy (TLS terminator, runtime via Docker) | per release | https://github.com/caddyserver/caddy                              |

## Eclipse Public License 1.0 (`EPL-1.0`) and GNU LGPL 2.1 (`LGPL-2.1`) — dual

| Name                                       | Version   | Source                                                            |
|--------------------------------------------|-----------|-------------------------------------------------------------------|
| `ch.qos.logback:logback-classic`           | 1.5.18    | https://github.com/qos-ch/logback                                 |

Logback is dual-licensed under EPL-1.0 and LGPL-2.1. Either may be chosen at the user's option.

## BSD 2-Clause (`BSD-2-Clause`)

| Name                                       | Version   | Source                                                            |
|--------------------------------------------|-----------|-------------------------------------------------------------------|
| `org.postgresql:postgresql` (PgJDBC)       | 42.7.5    | https://github.com/pgjdbc/pgjdbc                                  |

## Apache License 2.0 — Kotlin standard test library

| Name                                       | Version   | Source                                                            |
|--------------------------------------------|-----------|-------------------------------------------------------------------|
| `org.jetbrains.kotlin:kotlin-test`         | (matches Kotlin) | https://github.com/JetBrains/kotlin                        |

---

## Runtime infrastructure (Docker images, not embedded)

These are pulled at deploy time, not bundled into the fat JAR, but they are part of what runs in production.

| Component                | License           | Source                                                  |
|--------------------------|-------------------|---------------------------------------------------------|
| PostgreSQL (Docker image)| PostgreSQL License| https://www.postgresql.org/about/licence/               |
| Meilisearch (Docker image)| MIT              | https://github.com/meilisearch/meilisearch              |
| Caddy (Docker image)     | Apache-2.0        | https://github.com/caddyserver/caddy                    |
| OpenJDK 21 (build base image) | GPLv2 with Classpath Exception | https://openjdk.org/legal/gplv2+ce.html |

---

## Asset attributions (runtime / non-code)

These cover assets that may be served by, or reference resources from, the GitHub Store ecosystem the backend belongs to. Listed here so the backend's distribution carries the same notices the client does.

- **Material Symbols** — by Google, licensed Apache-2.0. https://github.com/google/material-design-icons
- **ziadOUA palette / iconography** — used with the upstream's stated license terms; see the upstream repository for current terms.

---

## Operator and project attribution

GitHub Store backend
Copyright 2026 GitHub Store contributors
Licensed under the Apache License, Version 2.0. See [`LICENSE`](./LICENSE) for the full text.

The "GitHub" name is a trademark of GitHub, Inc. The GitHub Store project is independent of, and not endorsed by, GitHub, Inc.

Last updated: 2026-04-25
