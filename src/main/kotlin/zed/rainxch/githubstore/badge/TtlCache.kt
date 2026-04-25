package zed.rainxch.githubstore.badge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration

class TtlCache<T>(private val ttl: Duration) {

    private val log = LoggerFactory.getLogger(TtlCache::class.java)

    private data class Entry<T>(val value: T, val storedAt: Instant)

    @Volatile
    private var entry: Entry<T>? = null
    private val mutex = Mutex()

    suspend fun getOrFetch(fetch: suspend () -> T): T {
        entry?.let { e ->
            if (e.storedAt.plusMillis(ttl.inWholeMilliseconds).isAfter(Instant.now())) {
                return e.value
            }
        }
        return mutex.withLock {
            entry?.let { e ->
                if (e.storedAt.plusMillis(ttl.inWholeMilliseconds).isAfter(Instant.now())) {
                    return@withLock e.value
                }
            }
            try {
                val fresh = fetch()
                entry = Entry(fresh, Instant.now())
                fresh
            } catch (e: Exception) {
                val stale = entry?.value
                if (stale != null) {
                    log.warn("TtlCache fetch failed; serving stale: {}", e.message)
                    stale
                } else {
                    throw e
                }
            }
        }
    }
}
