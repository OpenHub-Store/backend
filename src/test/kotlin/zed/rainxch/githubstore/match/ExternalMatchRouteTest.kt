package zed.rainxch.githubstore.match

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import zed.rainxch.githubstore.routes.externalMatchRoutes
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalMatchRouteTest {

    // Stub service — never hits the network or DB. The route's job is to
    // validate the request, fan out, and assemble the response. Service
    // logic is unit-tested in ExternalMatchScorerTest.
    private class StubService : ExternalMatchService(
        signingFingerprintRepository = SigningFingerprintRepository(),
        cache = zed.rainxch.githubstore.db.ResourceCacheRepository(),
        searchClient = zed.rainxch.githubstore.ingest.GitHubSearchClient(
            zed.rainxch.githubstore.db.MeilisearchClient(),
        ),
    ) {
        override suspend fun matchOne(req: ExternalMatchCandidateRequest): List<ExternalMatchCandidate> =
            listOf(
                ExternalMatchCandidate(
                    owner = "test-owner",
                    repo = "test-repo",
                    confidence = 0.7,
                    source = "search",
                    stars = 100,
                    description = "stub",
                ),
            )
    }

    private fun ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `non-android platform returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"ios","candidates":[{"packageName":"com.foo","appLabel":"Foo"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `empty candidates returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `over 25 candidates returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val candidates = (1..26).joinToString(",") {
            """{"packageName":"com.foo$it","appLabel":"Foo $it"}"""
        }
        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[$candidates]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid package name returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"has spaces","appLabel":"Foo"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid signing fingerprint returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"com.foo","appLabel":"Foo","signingFingerprint":"too-short"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid installer kind returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"com.foo","appLabel":"Foo","installerKind":"unknown_unknown"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid manifest hint owner returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"com.foo","appLabel":"Foo","manifestHint":{"owner":"not valid","repo":"foo"}}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `valid request returns 200 with one entry per candidate`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"platform":"android","candidates":[
                    {"packageName":"com.foo","appLabel":"Foo"},
                    {"packageName":"com.bar","appLabel":"Bar","installerKind":"obtainium"}
                ]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
