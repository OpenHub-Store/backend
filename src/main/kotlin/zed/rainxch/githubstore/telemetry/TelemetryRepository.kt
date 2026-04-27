package zed.rainxch.githubstore.telemetry

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.db.TelemetryEvents
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

open class TelemetryRepository {

    private val json = Json { encodeDefaults = false }

    open fun insertBatch(events: List<TelemetryEvent>) {
        if (events.isEmpty()) return
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        transaction {
            TelemetryEvents.batchInsert(events) { event ->
                this[TelemetryEvents.ts] = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(event.timestamp),
                    ZoneOffset.UTC,
                )
                this[TelemetryEvents.name] = event.name
                this[TelemetryEvents.sessionId] = event.sessionId
                this[TelemetryEvents.platform] = event.platform
                this[TelemetryEvents.appVersion] = event.appVersion
                this[TelemetryEvents.props] = event.props?.let { json.encodeToString(it) } ?: "{}"
                this[TelemetryEvents.receivedAt] = now
            }
        }
    }
}
