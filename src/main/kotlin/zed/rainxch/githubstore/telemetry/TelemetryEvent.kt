package zed.rainxch.githubstore.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val TelemetryJson = Json { encodeDefaults = false }

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

// Per-event allowed-prop-key allowlist. Keys outside the list are stripped
// silently (symmetric with the event-name drop-on-floor design — schema drift
// from a stale client must not 4xx). Mirrors roadmap/E6_CLIENT_HANDOFF.md §3
// — keep both in sync when adding a new event or prop.
object PropsSchema {
    val BY_EVENT: Map<String, Set<String>> = mapOf(
        // session
        "app_launched" to emptySet(),
        "session_duration" to setOf("seconds"),

        // import (E1 / E2)
        "import_scan_started" to setOf("platform"),
        "import_scan_completed" to setOf("candidate_count", "duration_ms"),
        "import_match_attempted" to setOf("strategy", "confidence_bucket"),
        "import_auto_linked" to setOf("count"),
        "import_manually_linked" to setOf("count"),
        "import_skipped" to setOf("count"),

        // reliability (E3)
        "crash" to setOf("category", "platform"),
        "operation_failed" to setOf("op", "error_code"),

        // performance (E4)
        "cold_start_ms" to setOf("platform", "bucket"),
        "first_paint_ms" to setOf("screen", "bucket"),
        "cache_hit" to setOf("cache_name"),
        "cache_miss" to setOf("cache_name"),

        // proxy (E5)
        "proxy_configured" to setOf("type"),
        "proxy_used" to setOf("success"),
        "mirror_used" to setOf("preset", "success"),

        // discovery / engagement
        "update_installed" to emptySet(),
        "search_executed" to setOf("result_count_bucket"),
        "details_viewed" to setOf("from"),
    )

    fun allowedKeys(event: String): Set<String> = BY_EVENT[event] ?: emptySet()
}
