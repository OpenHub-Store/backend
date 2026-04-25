package zed.rainxch.githubstore.db

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.model.EventRequest
import zed.rainxch.githubstore.util.PrivacyHash
import java.time.OffsetDateTime

class EventRepository {

    fun insertBatch(events: List<EventRequest>) {
        transaction {
            for (event in events) {
                Events.insert {
                    it[ts] = OffsetDateTime.now()
                    it[deviceId] = PrivacyHash.hash(event.deviceId)
                    it[platform] = event.platform
                    it[appVersion] = event.appVersion
                    it[eventType] = event.eventType
                    it[repoId] = event.repoId
                    it[queryHash] = event.queryHash
                    it[resultCount] = event.resultCount
                    it[success] = event.success
                    it[errorCode] = event.errorCode
                }
            }
        }
    }
}
