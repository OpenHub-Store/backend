package zed.rainxch.githubstore.announcements

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Single source of truth for announcement JSON parsing. ignoreUnknownKeys lets
// the schema grow forwards without breaking older deploys mid-rollout;
// isLenient=false keeps strict-quotes behavior so a typo'd file fails loud at
// load time instead of silently parsing as something else. The encodeDefaults
// flag matters for ETag canonicalization -- two items that differ only in
// "did the author write the default explicitly" must hash identically.
internal val AnnouncementsJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
    prettyPrint = false
}

// Wire format mirrors the client DTO verbatim. Field names use camelCase (NOT
// the snake_case the rest of /v1 uses) because the contract in
// docs/backend/announcements-endpoint.md is the client's Kotlin DTO and the
// app deserializes via the property names directly. Renaming is breaking and
// requires bumping `version` in the envelope.
@Serializable
data class AnnouncementDto(
    val id: String,
    val publishedAt: String,
    val expiresAt: String? = null,
    val severity: String,
    val category: String,
    val title: String,
    val body: String,
    val ctaUrl: String? = null,
    val ctaLabel: String? = null,
    val dismissible: Boolean = true,
    val requiresAcknowledgment: Boolean = false,
    val minVersionCode: Int? = null,
    val maxVersionCode: Int? = null,
    val platforms: List<String>? = null,
    val installerTypes: List<String>? = null,
    val iconHint: String? = null,
    val i18n: Map<String, AnnouncementLocaleDto> = emptyMap(),
)

@Serializable
data class AnnouncementLocaleDto(
    val title: String? = null,
    val body: String? = null,
    val ctaUrl: String? = null,
    val ctaLabel: String? = null,
)

@Serializable
data class AnnouncementsResponse(
    val version: Int = 1,
    val fetchedAt: String,
    val items: List<AnnouncementDto>,
)
