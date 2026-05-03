package zed.rainxch.githubstore.announcements

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

// Holds the loaded announcement set. Loaded once at startup via start(). The
// deploy is the refresh trigger -- the read-only container rootfs has no
// in-place edit path anyway, so a file watcher / tick would be busywork. If
// that ever changes (e.g. mounting an editable ANNOUNCEMENTS_DIR at runtime),
// call reload() from a worker.
class AnnouncementsRegistry(
    private val loader: AnnouncementLoader = AnnouncementLoader(),
) {
    private val log = LoggerFactory.getLogger(AnnouncementsRegistry::class.java)

    data class Served(
        val response: AnnouncementsResponse,
        val etag: String,
    )

    private val items = AtomicReference<List<AnnouncementDto>>(emptyList())

    // Memoized serve() output, scoped to (items snapshot ref, minute bucket).
    // Active-set + ETag are pure functions of those two inputs; recomputing per
    // request was wasted work since traffic is steady-state. Holds a CachedServe
    // instead of Served because the per-request fetchedAt has to stay live.
    private val cache = AtomicReference<CachedServe?>(null)

    private data class CachedServe(
        val itemsRef: List<AnnouncementDto>,
        val minute: Instant,
        val activeItems: List<AnnouncementDto>,
        val etag: String,
    )

    fun start() {
        reload()
    }

    fun reload() {
        val loaded = loader.load().sortedByDescending { it.publishedAt }
        items.set(loaded)
        cache.set(null)
        log.info("Announcements registry: {} item(s) loaded", loaded.size)
    }

    fun loadedCount(): Int = items.get().size

    fun serve(now: Instant = Instant.now()): Served {
        val snapshot = items.get()
        val bucket = now.truncatedTo(ChronoUnit.MINUTES)
        val cached = cache.get()
        val hit = cached != null && cached.itemsRef === snapshot && cached.minute == bucket

        val (active, etag) = if (hit) {
            cached!!.activeItems to cached.etag
        } else {
            val computedActive = filterActive(snapshot, now)
            val computedEtag = etagOf(computedActive)
            // Best-effort cache; if two threads race on a minute boundary they
            // both compute and one wins -- never serves stale, just briefly
            // duplicates work.
            cache.set(CachedServe(snapshot, bucket, computedActive, computedEtag))
            computedActive to computedEtag
        }

        return Served(
            response = AnnouncementsResponse(
                version = 1,
                fetchedAt = now.toString(),
                items = active,
            ),
            etag = etag,
        )
    }

    private fun filterActive(snapshot: List<AnnouncementDto>, now: Instant): List<AnnouncementDto> =
        snapshot.filter { item ->
            val expiresAt = item.expiresAt ?: return@filter true
            try {
                Instant.parse(expiresAt).isAfter(now)
            } catch (_: Exception) {
                // Validator already rejected unparseable timestamps; defensive
                // fallback treats a corrupt expiresAt as "no expiry".
                true
            }
        }

    private companion object {
        // ETag hashes the items only -- never fetchedAt, otherwise every
        // call would mint a fresh tag even when content is identical.
        // Active-filter changes (an item expiring) DO change the ETag
        // because the items list shrinks.
        fun etagOf(items: List<AnnouncementDto>): String {
            val canonical = AnnouncementsJson.encodeToString(
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
