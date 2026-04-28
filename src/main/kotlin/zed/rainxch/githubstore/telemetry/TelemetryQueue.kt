package zed.rainxch.githubstore.telemetry

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

open class TelemetryQueue(private val repository: TelemetryRepository) {

    private val log = LoggerFactory.getLogger(TelemetryQueue::class.java)

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            log.warn("Telemetry insert failed", e)
        }
    )

    // Bounds in-flight inserts so a traffic burst can't queue unbounded
    // coroutines, each holding a Hikari connection (pool size 20). 2 is
    // enough headroom for normal load while leaving the rest of the pool
    // for live request handlers.
    private val gate = Semaphore(permits = 2)

    open fun submit(events: List<TelemetryEvent>) {
        if (events.isEmpty()) return
        scope.launch {
            gate.withPermit {
                repository.insertBatch(events)
            }
        }
    }
}
