FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew buildFatJar --no-daemon

FROM eclipse-temurin:21-jre
RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app
COPY --from=build /app/build/libs/github-store-backend.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
# 50% of the container's allocated RAM. Container is co-scheduled with
# Postgres and Meilisearch on one 8GB VPS; without an explicit ceiling the
# JVM reads host RAM and sets -Xmx to ~2GB, which starves the other two
# services if traffic bursts. -XX:+ExitOnOutOfMemoryError makes OOMs
# crash the container so docker-compose restarts it instead of leaving a
# zombie.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
