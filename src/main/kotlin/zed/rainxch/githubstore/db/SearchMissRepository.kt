package zed.rainxch.githubstore.db

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.security.MessageDigest
import java.time.OffsetDateTime

class SearchMissRepository {

    fun logMiss(query: String) {
        val hash = sha256(query.lowercase().trim())
        transaction {
            SearchMisses.upsert(SearchMisses.queryHash,
                onUpdate = {
                    it[SearchMisses.missCount] = SearchMisses.missCount + 1
                    it[SearchMisses.lastSeenAt] = OffsetDateTime.now()
                    it[SearchMisses.querySample] = query.take(100)
                }
            ) {
                it[queryHash] = hash
                it[querySample] = query.take(100)
                it[missCount] = 1
                it[lastSeenAt] = OffsetDateTime.now()
            }
        }
    }

    /**
     * Returns top N misses that haven't been processed recently, with their raw query samples.
     * A miss is "ready to process" if last_processed_at is null OR older than 7 days.
     */
    fun topUnprocessed(limit: Int = 10): List<MissEntry> = transaction {
        val cutoff = OffsetDateTime.now().minusDays(7)
        SearchMisses
            .selectAll()
            .where {
                SearchMisses.querySample.isNotNull() and
                    (SearchMisses.lastProcessedAt.isNull() or
                        (SearchMisses.lastProcessedAt less cutoff))
            }
            .orderBy(SearchMisses.missCount to SortOrder.DESC)
            .limit(limit)
            .map {
                MissEntry(
                    queryHash = it[SearchMisses.queryHash],
                    querySample = it[SearchMisses.querySample] ?: "",
                    missCount = it[SearchMisses.missCount],
                )
            }
    }

    fun markProcessed(queryHash: String) {
        transaction {
            SearchMisses.update({ SearchMisses.queryHash eq queryHash }) {
                it[lastProcessedAt] = OffsetDateTime.now()
            }
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    data class MissEntry(
        val queryHash: String,
        val querySample: String,
        val missCount: Int,
    )
}
