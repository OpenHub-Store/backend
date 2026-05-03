package zed.rainxch.githubstore.announcements

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.githubstore.routes.healthRoutes
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteAnnouncementsTest {

    private val futureExpiry: String = Instant.now().plus(365, ChronoUnit.DAYS).toString()

    @Test
    fun `health includes announcements loaded count when items are loaded`() = testApplication {
        val dir = Files.createTempDirectory("health-announcements-")
        try {
            // Write two valid items so loadedCount() == 2.
            writeJson(dir, "a-1", validJson("a-1"))
            writeJson(dir, "a-2", validJson("a-2", publishedAt = "2026-05-01T00:00:00Z"))

            val registry = AnnouncementsRegistry(
                loader = AnnouncementLoader(classpathDir = "__nonexistent__", explicitDir = dir),
            ).also { it.start() }

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
                routing {
                    route("/v1") {
                        // Lambda overload sidesteps spinning up a real
                        // MeilisearchClient (CIO HttpClient pool) and a real
                        // JDBC connection in a unit-test context. Status code
                        // is incidental; we assert the response shape.
                        healthRoutes(
                            announcements = registry,
                            meilisearchHealthy = { true },
                            postgresHealthy = { "ok" },
                        )
                    }
                }
            }

            val resp = client.get("/v1/health")
            // The response shape must include the new field regardless of
            // whether postgres/meilisearch are reachable in CI.
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertTrue(body.containsKey("announcements"), "missing announcements field: $body")
            assertEquals(2, body["announcements"]!!.jsonPrimitive.content.toInt())
        } finally {
            cleanup(dir)
        }
    }

    private fun writeJson(dir: Path, id: String, json: String) {
        dir.resolve("$id.json").writeText(json)
    }

    private fun validJson(
        id: String,
        publishedAt: String = "2026-06-15T00:00:00Z",
        expiresAt: String? = futureExpiry,
        title: String = "Test announcement",
        body: String = "B".repeat(60),
    ): String = buildString {
        append("""{"id":"$id","publishedAt":"$publishedAt",""")
        if (expiresAt != null) append(""""expiresAt":"$expiresAt",""")
        append(""""severity":"INFO","category":"NEWS","title":"$title","body":"$body"}""")
    }

    private fun cleanup(dir: Path) {
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
