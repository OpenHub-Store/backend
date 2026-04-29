package zed.rainxch.githubstore.mirrors

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Thread-safe in-memory store for runtime mirror health. Restart-volatile by
// design -- worker re-pings within 30s of startup and the catalog endpoint
// returns UNKNOWN until then. Avoids a DB table for ~6 rows that change hourly.
//
// Tracks consecutive-down streaks so the worker can WARN once when a mirror
// has been dead long enough to be probably-removable. Streak resets on any
// non-DOWN ping outcome.
class MirrorStatusRegistry {

    private val log = LoggerFactory.getLogger(MirrorStatusRegistry::class.java)

    data class Snapshot(
        val status: MirrorStatus,
        val latencyMs: Long?,
        val lastCheckedAt: Instant?,
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val downStreaks = ConcurrentHashMap<String, Int>()
    private val warnedFor = ConcurrentHashMap<String, Boolean>()

    fun snapshot(id: String): Snapshot =
        snapshots[id] ?: Snapshot(MirrorStatus.UNKNOWN, null, null)

    fun all(): Map<String, Snapshot> = snapshots.toMap()

    // Update one mirror's status. Returns true if this update crossed the
    // CONSECUTIVE_DOWN_WARN threshold for the first time -- caller logs.
    fun update(id: String, status: MirrorStatus, latencyMs: Long?): Boolean {
        snapshots[id] = Snapshot(status, latencyMs, Instant.now())

        return if (status == MirrorStatus.DOWN) {
            val streak = downStreaks.merge(id, 1, Int::plus) ?: 1
            if (streak >= CONSECUTIVE_DOWN_WARN && warnedFor[id] != true) {
                warnedFor[id] = true
                log.warn(
                    "Mirror '{}' has been DOWN for {} consecutive cycles -- consider removing from MirrorPresets.ALL",
                    id, streak,
                )
                true
            } else false
        } else {
            // Recovery: reset streak + clear warn-once flag.
            if (downStreaks.remove(id) != null) {
                warnedFor.remove(id)
                log.info("Mirror '{}' recovered: status={} latency={}ms", id, status, latencyMs)
            }
            false
        }
    }

    private companion object {
        // 7 cycles at 1h each = ~7 hours of consecutive failure before we WARN.
        // Long enough to ride out short outages, short enough that a permanently
        // dead mirror gets flagged within an operator's normal review window.
        const val CONSECUTIVE_DOWN_WARN = 7
    }
}
