package zed.rainxch.githubstore.ingest

import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class WorkerSupervisor {
    private val log = LoggerFactory.getLogger(WorkerSupervisor::class.java)
    private val ticks = ConcurrentHashMap<String, Instant>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun register(name: String, job: Job) {
        jobs[name] = job
    }

    fun recordTick(name: String) {
        ticks[name] = Instant.now()
    }

    fun lastTicks(): Map<String, Instant> = ticks.toMap()

    fun cancelAll() {
        jobs.forEach { (name, job) ->
            log.info("Cancelling worker job: {}", name)
            job.cancel()
        }
        jobs.clear()
    }
}
