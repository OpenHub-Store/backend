package zed.rainxch.githubstore.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryAllowlistTest {

    @Test
    fun `allowlist contains every event in the locked schema`() {
        // Source of truth: roadmap/FREE_FEATURES.md E6. Update both when
        // adding a new event type. This test exists so a typo in
        // TelemetryAllowlist.EVENTS fails CI loudly.
        val expected = setOf(
            "app_launched", "session_duration",
            "import_scan_started", "import_scan_completed", "import_match_attempted",
            "import_auto_linked", "import_manually_linked", "import_skipped",
            "crash", "operation_failed",
            "cold_start_ms", "first_paint_ms", "cache_hit", "cache_miss",
            "proxy_configured", "proxy_used", "mirror_used",
            "update_installed", "search_executed", "details_viewed",
        )
        assertEquals(expected, TelemetryAllowlist.EVENTS)
    }

    @Test
    fun `allowlist rejects PII-shaped event names`() {
        // Defense in depth — if a future schema change accidentally
        // introduces something user-identifying, this catches it.
        val forbidden = listOf(
            "user_id", "email", "search_query", "repo_name",
            "github_username", "device_id", "ip_address",
        )
        forbidden.forEach { name ->
            assertFalse(name in TelemetryAllowlist.EVENTS, "forbidden event $name leaked into allowlist")
        }
    }

    @Test
    fun `every allowlisted name fits the route's MAX_NAME_LEN cap`() {
        // The route enforces a 64-char cap. If an event name in the schema
        // ever exceeds it, the route would reject every batch carrying that
        // event — silent dropped data. Assert the cap holds at the schema.
        TelemetryAllowlist.EVENTS.forEach { name ->
            assertTrue(name.length <= 64, "$name exceeds the 64-char route cap")
        }
    }
}
