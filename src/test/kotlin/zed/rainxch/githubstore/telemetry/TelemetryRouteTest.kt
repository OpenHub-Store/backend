package zed.rainxch.githubstore.telemetry

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import zed.rainxch.githubstore.routes.telemetryRoutes
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryRouteTest {

    private class FakeRepo(private val delayMs: Long = 0L) : TelemetryRepository() {
        val inserted = mutableListOf<TelemetryEvent>()
        override suspend fun insertBatch(events: List<TelemetryEvent>) {
            if (delayMs > 0) delay(delayMs)
            inserted += events
        }
    }

    // Drains the events synchronously into the underlying repo so route-level
    // assertions don't race the fire-and-forget scope of the real
    // TelemetryQueue. The real queue is exercised in `route returns immediately`
    // below, which uses the production TelemetryQueue against a slow repo.
    private class FakeQueue(private val repo: FakeRepo) : TelemetryQueue(repo) {
        override fun submit(events: List<TelemetryEvent>) {
            runBlocking { repo.insertBatch(events) }
        }
    }

    private fun ApplicationTestBuilder.setupApp(queue: TelemetryQueue) {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { route("/v1") { telemetryRoutes(queue) } }
        }
    }

    private fun event(
        name: String,
        sessionId: String = "s1",
        platform: String = "android",
        propsJson: String? = null,
    ): String {
        val propsPart = if (propsJson != null) ""","props":$propsJson""" else ""
        return """{"name":"$name","sessionId":"$sessionId","timestamp":1700000000000,"platform":"$platform","appVersion":"1.7.0"$propsPart}"""
    }

    @Test
    fun `valid batch of allowlisted events returns 204 and persists every event`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

        val body = """{"events":[${event("app_launched")},${event("search_executed", propsJson = """{"result_count_bucket":"1-5"}""")}]}"""
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
        setupApp(FakeQueue(repo))

        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(repo.inserted.isEmpty())
    }

    @Test
    fun `non-allowlisted events drop on floor and yield 200 with counts`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

        val body = """{"events":[
            ${event("search_executed", propsJson = """{"result_count_bucket":"1-5"}""")},
            ${event("user_email_collected")},
            ${event("install_started")},
            ${event("install_succeeded")},
            ${event("crash", propsJson = """{"category":"other","platform":"android"}""")}
        ]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(""""accepted":2""" in text, "body should report 2 accepted, got: $text")
        assertTrue(""""dropped":3""" in text, "body should report 3 dropped, got: $text")
        assertEquals(2, repo.inserted.size)
        assertEquals(setOf("search_executed", "crash"), repo.inserted.map { it.name }.toSet())
    }

    @Test
    fun `batch over 100 events returns 400`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

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
        setupApp(FakeQueue(repo))

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
        setupApp(FakeQueue(repo))

        val longSession = "s".repeat(129)
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[${event("app_launched", sessionId = longSession)}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `all-non-allowlisted batch yields 200 with accepted=0`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

        val body = """{"events":[${event("totally_unknown_event")},${event("another_made_up_one")}]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(""""accepted":0""" in text)
        assertTrue(""""dropped":2""" in text)
        assertTrue(repo.inserted.isEmpty())
    }

    // T3: body-size pre-check
    @Test
    fun `payload over 256KB returns 413`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

        // 100 events with 3KB of padding each → ~300KB, well over the 256KB cap.
        val padding = "p".repeat(3000)
        val many = (1..100).joinToString(",") {
            """{"name":"app_launched","sessionId":"$padding","timestamp":1,"platform":"android"}"""
        }
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[$many]}""")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(repo.inserted.isEmpty())
    }

    // T4: per-event props serialized over 2KB
    @Test
    fun `event with props over 2KB returns 400`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

        val bigVal = "v".repeat(2100)
        val body = """{"events":[${event("crash", propsJson = """{"category":"$bigVal"}""")}]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(repo.inserted.isEmpty())
    }

    // T5: keys outside the per-event allowlist are stripped silently
    @Test
    fun `disallowed prop keys are stripped and surface as 200 with counts`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

        val body = """{"events":[${event("crash", propsJson = """{"category":"other","platform":"android","secret_pii":"abc"}""")}]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, repo.inserted.size)
        val storedKeys = repo.inserted.single().props?.keys ?: emptySet()
        assertEquals(setOf("category", "platform"), storedKeys)
    }

    // T6: empty-string event name returns 400, never silent drop
    @Test
    fun `empty event name returns 400 invalid_event_name`() = testApplication {
        val repo = FakeRepo()
        setupApp(FakeQueue(repo))

        val body = """{"events":[${event("")}]}"""
        val response = client.post("/v1/telemetry/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("invalid_event_name" in response.bodyAsText())
        assertTrue(repo.inserted.isEmpty())
    }

    // T10: route does not block on the insert. The slow repo here would add
    // 5s to the response time if the route awaited persistence.
    @Test
    fun `route returns immediately even if the repo is slow`() = testApplication {
        val slowRepo = FakeRepo(delayMs = 5_000L)
        // Real production TelemetryQueue, not the synchronous FakeQueue —
        // this is the path that proves fire-and-forget.
        val realQueue = TelemetryQueue(slowRepo)
        setupApp(realQueue)

        val body = """{"events":[${event("app_launched")}]}"""
        val elapsed = measureTimeMillis {
            val response = client.post("/v1/telemetry/events") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

        assertTrue(elapsed < 1_000, "expected <1s response, took ${elapsed}ms — route is awaiting the insert")
    }
}
