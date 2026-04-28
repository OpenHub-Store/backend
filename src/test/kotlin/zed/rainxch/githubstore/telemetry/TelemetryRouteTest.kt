package zed.rainxch.githubstore.telemetry

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import zed.rainxch.githubstore.routes.telemetryRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryRouteTest {

    private class FakeRepo : TelemetryRepository() {
        val inserted = mutableListOf<TelemetryEvent>()
        override suspend fun insertBatch(events: List<TelemetryEvent>) {
            inserted += events
        }
    }

    // Drains the events synchronously into the underlying repo so route-level
    // assertions don't race the fire-and-forget scope from the real
    // TelemetryQueue. Real wiring is covered in T10's slow-repo test.
    private class FakeQueue(private val repo: FakeRepo) : TelemetryQueue(repo) {
        override fun submit(events: List<TelemetryEvent>) {
            kotlinx.coroutines.runBlocking { repo.insertBatch(events) }
        }
    }

    private fun ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun event(name: String, sessionId: String = "s1", platform: String = "android"): String =
        """{"name":"$name","sessionId":"$sessionId","timestamp":1700000000000,"platform":"$platform","appVersion":"1.7.0"}"""

    @Test
    fun `valid batch of allowlisted events returns 204 and persists every event`() = testApplication {
        val repo = FakeRepo()
        val queue = FakeQueue(repo)
        installPlugins()
        application { routing { route("/v1") { telemetryRoutes(queue) } } }

        val body = """{"events":[${event("app_launched")},${event("search_executed")}]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(2, repo.inserted.size)
        assertEquals(setOf("app_launched", "search_executed"), repo.inserted.map { it.name }.toSet())
    }

    @Test
    fun `empty batch returns 204 without touching the repository`() = testApplication {
        val repo = FakeRepo()
        val queue = FakeQueue(repo)
        installPlugins()
        application { routing { route("/v1") { telemetryRoutes(queue) } } }

        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(repo.inserted.isEmpty())
    }

    @Test
    fun `non-allowlisted events are dropped on the floor while allowlisted ones still persist`() = testApplication {
        val repo = FakeRepo()
        val queue = FakeQueue(repo)
        installPlugins()
        application { routing { route("/v1") { telemetryRoutes(queue) } } }

        val body = """{"events":[
            ${event("search_executed")},
            ${event("user_email_collected")},
            ${event("install_started")},
            ${event("install_succeeded")},
            ${event("crash")}
        ]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        // 5 submitted; user_email_collected + install_started + install_succeeded
        // are not in the allowlist → only search_executed and crash persist.
        assertEquals(2, repo.inserted.size)
        assertEquals(setOf("search_executed", "crash"), repo.inserted.map { it.name }.toSet())
    }

    @Test
    fun `batch over 100 events returns 400`() = testApplication {
        val repo = FakeRepo()
        val queue = FakeQueue(repo)
        installPlugins()
        application { routing { route("/v1") { telemetryRoutes(queue) } } }

        val many = (1..101).joinToString(",") { event("app_launched", sessionId = "s$it") }
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[$many]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(repo.inserted.isEmpty(), "no events should persist on a rejected batch")
    }

    @Test
    fun `event name longer than 64 chars returns 400`() = testApplication {
        val repo = FakeRepo()
        val queue = FakeQueue(repo)
        installPlugins()
        application { routing { route("/v1") { telemetryRoutes(queue) } } }

        val longName = "x".repeat(65)
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[${event(longName)}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(repo.inserted.isEmpty())
    }

    @Test
    fun `session id longer than 128 chars returns 400`() = testApplication {
        val repo = FakeRepo()
        val queue = FakeQueue(repo)
        installPlugins()
        application { routing { route("/v1") { telemetryRoutes(queue) } } }

        val longSession = "s".repeat(129)
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[${event("app_launched", sessionId = longSession)}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `every event in a batch where all are non-allowlisted yields 204 with zero inserts`() = testApplication {
        val repo = FakeRepo()
        val queue = FakeQueue(repo)
        installPlugins()
        application { routing { route("/v1") { telemetryRoutes(queue) } } }

        val body = """{"events":[${event("totally_unknown_event")},${event("another_made_up_one")}]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        // Drop-on-floor with no error response. Client thinks it succeeded;
        // operator sees the drop-rate metric and notices schema drift.
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(repo.inserted.isEmpty())
    }
}
