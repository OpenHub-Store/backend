package zed.rainxch.githubstore.announcements

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

// Holds the loaded announcement set. Loaded once at startup. The deploy is
// the refresh trigger -- the read-only container rootfs has no in-place edit
// path anyway, so a file watcher / tick would be busywork. If that ever
// changes (e.g. mounting an editable ANNOUNCEMENTS_DIR at runtime), call
// reload() from a worker.
class AnnouncementsRegistry(
    private val loader: AnnouncementLoader = AnnouncementLoader(),
) {
    private val log = LoggerFactory.getLogger(AnnouncementsRegistry::class.java)

    data class Served(
        val response: AnnouncementsResponse,
        val etag: String,
    )

    private val items = AtomicReference<List<AnnouncementDto>>(emptyList())

    init {
        reload()
    }

    fun reload() {
        val loaded = loader.load().sortedByDescending { it.publishedAt }
        items.set(loaded)
        log.info("Announcements registry: {} item(s) loaded", loaded.size)
    }

    fun serve(now: Instant = Instant.now()): Served {
        val active = items.get().filter { item ->
            val expiresAt = item.expiresAt ?: return@filter true
            try {
                Instant.parse(expiresAt).isAfter(now)
            } catch (_: Exception) {
                // Validator already rejected unparseable timestamps; defensive
                // fallback treats a corrupt expiresAt as "no expiry".
                true
            }
        }
        return Served(
            response = AnnouncementsResponse(
                version = 1,
                fetchedAt = now.toString(),
                items = active,
            ),
            etag = etagOf(active),
        )
    }

    private companion object {
        private val ETAG_JSON = Json {
            prettyPrint = false
            encodeDefaults = true
        }

        // ETag hashes the items only -- never fetchedAt, otherwise every
        // call would mint a fresh tag even when content is identical.
        // Active-filter changes (an item expiring) DO change the ETag
        // because the items list shrinks.
        fun etagOf(items: List<AnnouncementDto>): String {
            val canonical = ETAG_JSON.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(AnnouncementDto.serializer()),
                items,
            )
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(canonical.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "\"${hex.take(16)}\""
        }
    }
}
