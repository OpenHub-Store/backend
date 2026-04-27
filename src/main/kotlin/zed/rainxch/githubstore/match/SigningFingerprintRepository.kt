package zed.rainxch.githubstore.match

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.db.SigningFingerprints
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
open class SigningFingerprintRepository {

    fun lookup(fingerprint: String): List<Pair<String, String>> = transaction {
        SigningFingerprints
            .selectAll()
            .where { SigningFingerprints.fingerprint eq fingerprint }
            .map { it[SigningFingerprints.owner] to it[SigningFingerprints.repo] }
    }

    /**
     * Seed dump for /v1/signing-seeds. Returns rows ordered by (observedAt,
     * fingerprint, owner, repo) so the cursor only needs to encode the most
     * recent boundary — never the row offset, which would skip rows under
     * concurrent writes.
     */
    open fun page(
        sinceMs: Long?,
        cursor: PageCursor?,
        limit: Int,
    ): SigningSeedPage = transaction {
        val rows = SigningFingerprints
            .selectAll()
            .where {
                var clause: Op<Boolean> = Op.TRUE
                if (sinceMs != null) {
                    clause = clause and (SigningFingerprints.observedAt greaterEq sinceMs)
                }
                if (cursor != null) {
                    // Composite seek: rows strictly after (observedAt, fingerprint,
                    // owner, repo). Standard "row-value comparison" pattern, expanded
                    // to ANDs/ORs since Exposed has no native row-value comparator.
                    val c = cursor
                    val seek = (SigningFingerprints.observedAt greater c.observedAt) or
                        ((SigningFingerprints.observedAt eq c.observedAt) and
                            (SigningFingerprints.fingerprint greater c.fingerprint)) or
                        ((SigningFingerprints.observedAt eq c.observedAt) and
                            (SigningFingerprints.fingerprint eq c.fingerprint) and
                            (SigningFingerprints.owner greater c.owner)) or
                        ((SigningFingerprints.observedAt eq c.observedAt) and
                            (SigningFingerprints.fingerprint eq c.fingerprint) and
                            (SigningFingerprints.owner eq c.owner) and
                            (SigningFingerprints.repo greater c.repo))
                    clause = clause and seek
                }
                clause
            }
            .orderBy(
                SigningFingerprints.observedAt to SortOrder.ASC,
                SigningFingerprints.fingerprint to SortOrder.ASC,
                SigningFingerprints.owner to SortOrder.ASC,
                SigningFingerprints.repo to SortOrder.ASC,
            )
            .limit(limit + 1) // peek one extra to know if there's more
            .map {
                SigningSeedRow(
                    fingerprint = it[SigningFingerprints.fingerprint],
                    owner = it[SigningFingerprints.owner],
                    repo = it[SigningFingerprints.repo],
                    observedAt = it[SigningFingerprints.observedAt],
                )
            }

        val hasMore = rows.size > limit
        val pageRows = if (hasMore) rows.dropLast(1) else rows
        val nextCursor = if (hasMore && pageRows.isNotEmpty()) {
            val last = pageRows.last()
            PageCursor(last.observedAt, last.fingerprint, last.owner, last.repo)
        } else null

        SigningSeedPage(rows = pageRows, nextCursor = nextCursor)
    }

    fun upsertBatch(rows: List<SigningSeedRow>) {
        if (rows.isEmpty()) return
        transaction {
            // ignore=true means "skip on PK conflict" — the F-Droid ingester
            // re-runs daily and we don't need to update observed_at on rows
            // that were already seen. New (fingerprint, owner, repo) tuples
            // land normally; existing ones are no-ops.
            SigningFingerprints.batchInsert(rows, ignore = true) { row ->
                this[SigningFingerprints.fingerprint] = row.fingerprint
                this[SigningFingerprints.owner] = row.owner
                this[SigningFingerprints.repo] = row.repo
                this[SigningFingerprints.observedAt] = row.observedAt
            }
        }
    }

    data class SigningSeedPage(
        val rows: List<SigningSeedRow>,
        val nextCursor: PageCursor?,
    )

    data class PageCursor(
        val observedAt: Long,
        val fingerprint: String,
        val owner: String,
        val repo: String,
    ) {
        fun encode(): String {
            val raw = "$observedAt|$fingerprint|$owner|$repo"
            return Base64.UrlSafe.encode(raw.toByteArray()).trimEnd('=')
        }

        companion object {
            fun decode(token: String): PageCursor? = runCatching {
                val padded = token.padEnd(((token.length + 3) / 4) * 4, '=')
                val raw = Base64.UrlSafe.decode(padded).decodeToString()
                val parts = raw.split('|', limit = 4)
                if (parts.size != 4) return@runCatching null
                PageCursor(
                    observedAt = parts[0].toLong(),
                    fingerprint = parts[1],
                    owner = parts[2],
                    repo = parts[3],
                )
            }.getOrNull()
        }
    }
}
