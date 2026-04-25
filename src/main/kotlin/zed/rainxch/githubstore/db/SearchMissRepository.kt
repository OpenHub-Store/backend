package zed.rainxch.githubstore.db

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.security.MessageDigest
import java.text.Normalizer
import java.time.OffsetDateTime

class SearchMissRepository {

    fun logMiss(query: String, resultCount: Int = 0) {
        val canonical = canonicalize(query)
        if (canonical.isEmpty()) return
        val hash = sha256(canonical)
        transaction {
            SearchMisses.upsert(SearchMisses.queryHash,
                onUpdate = {
                    it[SearchMisses.missCount] = SearchMisses.missCount + 1
                    it[SearchMisses.lastSeenAt] = OffsetDateTime.now()
                    it[SearchMisses.resultCount] = resultCount
                }
            ) {
                it[queryHash] = hash
                it[missCount] = 1
                it[lastSeenAt] = OffsetDateTime.now()
                it[SearchMisses.resultCount] = resultCount
            }
        }
    }

    fun topUnprocessed(limit: Int = 10): List<MissEntry> = transaction {
        val cutoff = OffsetDateTime.now().minusDays(7)
        SearchMisses
            .selectAll()
            .where {
                SearchMisses.lastProcessedAt.isNull() or
                    (SearchMisses.lastProcessedAt less cutoff)
            }
            .orderBy(SearchMisses.missCount to SortOrder.DESC)
            .limit(limit)
            .map {
                MissEntry(
                    queryHash = it[SearchMisses.queryHash],
                    querySample = "",
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

    companion object {
        fun canonicalize(query: String): String {
            val normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).lowercase()
            val stripped = buildString(normalized.length) {
                for (ch in normalized) {
                    when {
                        ch.isLetterOrDigit() -> append(ch)
                        ch == '-' || ch == '_' || ch.isWhitespace() -> append(' ')
                    }
                }
            }
            return stripped.trim().replace(Regex("\\s+"), " ")
        }
    }

    data class MissEntry(
        val queryHash: String,
        val querySample: String,
        val missCount: Int,
    )
}
