package zed.rainxch.githubstore.announcements

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.githubstore.routes.announcementsRoutes
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnnouncementsRoutesTest {

    private val futureExpiry: String = Instant.now().plus(365, ChronoUnit.DAYS).toString()

    private fun ApplicationTestBuilder.setupApp(registry: AnnouncementsRegistry) {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing { route("/v1") { announcementsRoutes(registry) } }
        }
    }

    private fun makeRegistry(dir: Path) = AnnouncementsRegistry(
        // explicitDir bypasses both the env var and the classpath fallback.
        loader = AnnouncementLoader(classpathDir = "__nonexistent__", explicitDir = dir),
    )

    private fun tempDir(): Path = Files.createTempDirectory("announcements-test-")

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

    @Test
    fun `empty dir returns empty items envelope`() = testApplication {
        val dir = tempDir()
        try {
            setupApp(makeRegistry(dir))
            val resp = client.get("/v1/announcements")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(1, body["version"]!!.jsonPrimitive.content.toInt())
            assertNotNull(body["fetchedAt"])
            assertEquals(0, body["items"]!!.jsonArray.size)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `valid file is served`() = testApplication {
        val dir = tempDir()
        try {
            writeJson(dir, "2026-06-15-test", validJson("2026-06-15-test"))
            setupApp(makeRegistry(dir))
            val body = Json.parseToJsonElement(client.get("/v1/announcements").bodyAsText()).jsonObject
            val items = body["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals("2026-06-15-test", items[0].jsonObject["id"]!!.jsonPrimitive.content)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `expired items are filtered at serve time`() = testApplication {
        val dir = tempDir()
        try {
            writeJson(
                dir, "2020-old",
                validJson(
                    "2020-old",
                    publishedAt = "2020-01-01T00:00:00Z",
                    expiresAt = "2020-12-31T00:00:00Z",
                ),
            )
            writeJson(dir, "current", validJson("current"))
            setupApp(makeRegistry(dir))
            val items = Json.parseToJsonElement(client.get("/v1/announcements").bodyAsText())
                .jsonObject["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals("current", items[0].jsonObject["id"]!!.jsonPrimitive.content)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `items sorted by publishedAt descending`() = testApplication {
        val dir = tempDir()
        try {
            writeJson(dir, "old", validJson("old", publishedAt = "2026-01-01T00:00:00Z"))
            writeJson(dir, "new", validJson("new", publishedAt = "2026-06-01T00:00:00Z"))
            writeJson(dir, "mid", validJson("mid", publishedAt = "2026-03-01T00:00:00Z"))
            setupApp(makeRegistry(dir))
            val ids = Json.parseToJsonElement(client.get("/v1/announcements").bodyAsText())
                .jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf("new", "mid", "old"), ids)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `invalid file is dropped, valid siblings still served`() = testApplication {
        val dir = tempDir()
        try {
            writeJson(dir, "good", validJson("good"))
            writeJson(
                dir, "bad",
                """{"id":"bad","publishedAt":"yesterday","severity":"INFO","category":"NEWS","title":"x","body":"x"}""",
            )
            setupApp(makeRegistry(dir))
            val items = Json.parseToJsonElement(client.get("/v1/announcements").bodyAsText())
                .jsonObject["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals("good", items[0].jsonObject["id"]!!.jsonPrimitive.content)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `duplicate ids reject whole payload`() = testApplication {
        val dir = tempDir()
        try {
            writeJson(dir, "a-1", validJson("dup"))
            writeJson(dir, "a-2", validJson("dup"))
            setupApp(makeRegistry(dir))
            val items = Json.parseToJsonElement(client.get("/v1/announcements").bodyAsText())
                .jsonObject["items"]!!.jsonArray
            // Per spec: duplicate id rejects the whole payload, not just the
            // duplicate. Both items dropped.
            assertEquals(0, items.size)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `cache headers are set`() = testApplication {
        val dir = tempDir()
        try {
            setupApp(makeRegistry(dir))
            val resp = client.get("/v1/announcements")
            val cc = resp.headers[HttpHeaders.CacheControl]
            assertNotNull(cc)
            assertTrue(cc.contains("max-age=600"), "missing max-age=600: $cc")
            assertTrue(cc.contains("public"), "must be public: $cc")
            assertNotNull(resp.headers[HttpHeaders.ETag])
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `if-none-match returns 304`() = testApplication {
        val dir = tempDir()
        try {
            writeJson(dir, "x", validJson("x"))
            setupApp(makeRegistry(dir))
            val first = client.get("/v1/announcements")
            val etag = first.headers[HttpHeaders.ETag]!!
            val second = client.get("/v1/announcements") {
                header(HttpHeaders.IfNoneMatch, etag)
            }
            assertEquals(HttpStatusCode.NotModified, second.status)
        } finally {
            cleanup(dir)
        }
    }

    @Test
    fun `weak prefix on incoming etag still matches`() = testApplication {
        val dir = tempDir()
        try {
            writeJson(dir, "x", validJson("x"))
            setupApp(makeRegistry(dir))
            val first = client.get("/v1/announcements")
            val etag = first.headers[HttpHeaders.ETag]!!
            val second = client.get("/v1/announcements") {
                header(HttpHeaders.IfNoneMatch, "W/$etag")
            }
            assertEquals(HttpStatusCode.NotModified, second.status)
        } finally {
            cleanup(dir)
        }
    }

    private fun cleanup(dir: Path) {
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
