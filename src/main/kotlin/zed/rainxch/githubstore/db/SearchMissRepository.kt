package zed.rainxch.githubstore.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.transaction
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

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
