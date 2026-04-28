package zed.rainxch.githubstore.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import zed.rainxch.githubstore.model.EventRequest
import zed.rainxch.githubstore.util.PrivacyHash
import java.time.OffsetDateTime

class EventRepository {

    suspend fun insertBatch(events: List<EventRequest>) {
        if (events.isEmpty()) return
        val now = OffsetDateTime.now()
        newSuspendedTransaction(Dispatchers.IO) {
            Events.batchInsert(events) { event ->
                this[Events.ts] = now
                this[Events.deviceId] = PrivacyHash.hash(event.deviceId)
                this[Events.platform] = event.platform
                this[Events.appVersion] = event.appVersion
                this[Events.eventType] = event.eventType
                this[Events.repoId] = event.repoId
                this[Events.queryHash] = event.queryHash
                this[Events.resultCount] = event.resultCount
                this[Events.success] = event.success
                this[Events.errorCode] = event.errorCode
            }
        }
    }
}
