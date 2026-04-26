package zed.rainxch.githubstore.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TelemetryEvent(
    val name: String,
    val sessionId: String,
    val timestamp: Long,
    val platform: String? = null,
    val appVersion: String? = null,
    val props: JsonObject? = null,
)

@Serializable
data class TelemetryBatchRequest(
    val events: List<TelemetryEvent>,
)

// Single source of truth for which event names the backend will accept.
// Anything outside this set is dropped on the floor — defense against rogue
// extensions, forgery, and accidental schema drift from a buggy client build.
//
// Mirrors the schema in roadmap/FREE_FEATURES.md E6 verbatim. Update both
// when adding a new event type.
object TelemetryAllowlist {
    val EVENTS: Set<String> = setOf(
        // session
        "app_launched",
        "session_duration",

        // import (E1 / E2)
        "import_scan_started",
        "import_scan_completed",
        "import_match_attempted",
        "import_auto_linked",
        "import_manually_linked",
        "import_skipped",

        // reliability (E3)
        "crash",
        "operation_failed",

        // performance (E4)
        "cold_start_ms",
        "first_paint_ms",
        "cache_hit",
        "cache_miss",

        // proxy (E5)
        "proxy_configured",
        "proxy_used",
        "mirror_used",

        // discovery / engagement
        "update_installed",
        "search_executed",
        "details_viewed",
    )
}
