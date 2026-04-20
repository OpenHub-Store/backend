package zed.rainxch.githubstore.db

import org.jetbrains.exposed.sql.transactions.transaction
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Postgres full-text search fallback when Meilisearch is unavailable.
 */
class SearchRepository {

    fun search(
        query: String,
        platform: String? = null,
        sort: String = "relevance",
        limit: Int = 20,
        offset: Int = 0,
    ): List<RepoResponse> = transaction {
        val platformColumn = when (platform) {
            "android" -> "has_installers_android"
            "windows" -> "has_installers_windows"
            "macos" -> "has_installers_macos"
            "linux" -> "has_installers_linux"
            else -> null
        }

        val orderClause = when (sort) {
            "stars" -> "ORDER BY stars DESC, search_score DESC NULLS LAST"
            "recent" -> "ORDER BY latest_release_date DESC NULLS LAST, search_score DESC NULLS LAST"
            else -> "ORDER BY ts_rank(tsv_search, plainto_tsquery('english', ?)) DESC, search_score DESC NULLS LAST"
        }

        val sql = buildString {
            append("SELECT * FROM repos WHERE tsv_search @@ plainto_tsquery('english', ?)")
            if (platformColumn != null) {
                append(" AND $platformColumn = true")
            }
            append(" $orderClause")
            append(" LIMIT ? OFFSET ?")
        }

        val results = mutableListOf<RepoResponse>()

        exec(sql) { rs ->
            // We need to manually set parameters, but exec() doesn't support that.
            // Use a connection-level approach instead.
        }

        // Use connection directly for parameterized query
        val conn = this.connection.connection as java.sql.Connection
        val paramSql = buildString {
            append("SELECT id, full_name, owner, name, owner_avatar_url, description, default_branch, ")
            append("html_url, stars, forks, language, latest_release_date, latest_release_tag, ")
            append("has_installers_android, has_installers_windows, has_installers_macos, has_installers_linux, ")
            append("trending_score, popularity_score, updated_at_gh, created_at_gh ")
            append("FROM repos WHERE tsv_search @@ plainto_tsquery('english', ?)")
            if (platformColumn != null) {
                append(" AND $platformColumn = true")
            }
            append(" ")
            append(orderClause)
            append(" LIMIT ? OFFSET ?")
        }

        conn.prepareStatement(paramSql).use { stmt ->
            var paramIdx = 1
            stmt.setString(paramIdx++, query)
            if (sort == "relevance") {
                stmt.setString(paramIdx++, query) // for ts_rank in ORDER BY
            }
            stmt.setInt(paramIdx++, limit)
            stmt.setInt(paramIdx, offset)

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val releaseDateStr = rs.getString("latest_release_date")
                    val recencyDays = releaseDateStr?.let {
                        try {
                            val releaseDate = OffsetDateTime.parse(it)
                            ChronoUnit.DAYS.between(releaseDate.toInstant(), OffsetDateTime.now().toInstant())
                                .toInt().coerceAtLeast(0)
                        } catch (_: Exception) { null }
                    }

                    results.add(RepoResponse(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        fullName = rs.getString("full_name"),
                        owner = RepoOwner(
                            login = rs.getString("owner"),
                            avatarUrl = rs.getString("owner_avatar_url"),
                        ),
                        description = rs.getString("description"),
                        defaultBranch = rs.getString("default_branch"),
                        htmlUrl = rs.getString("html_url"),
                        stargazersCount = rs.getInt("stars"),
                        forksCount = rs.getInt("forks"),
                        language = rs.getString("language"),
                        topics = emptyList(),
                        releasesUrl = "${rs.getString("html_url")}/releases",
                        updatedAt = rs.getString("updated_at_gh"),
                        createdAt = rs.getString("created_at_gh"),
                        latestReleaseDate = releaseDateStr,
                        latestReleaseTag = rs.getString("latest_release_tag"),
                        releaseRecency = recencyDays,
                        releaseRecencyText = recencyDays?.let { formatRecency(it) },
                        hasInstallersAndroid = rs.getBoolean("has_installers_android"),
                        hasInstallersWindows = rs.getBoolean("has_installers_windows"),
                        hasInstallersMacos = rs.getBoolean("has_installers_macos"),
                        hasInstallersLinux = rs.getBoolean("has_installers_linux"),
                        trendingScore = rs.getObject("trending_score") as? Double,
                        popularityScore = rs.getObject("popularity_score") as? Double,
                    ))
                }
            }
        }

        results
    }

    private fun formatRecency(days: Int): String = when (days) {
        0 -> "Released today"
        1 -> "Released yesterday"
        else -> "Released $days days ago"
    }
}
