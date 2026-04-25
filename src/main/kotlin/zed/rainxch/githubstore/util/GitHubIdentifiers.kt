package zed.rainxch.githubstore.util

private val OWNER_RE = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
private val NAME_RE = Regex("^[A-Za-z0-9._-]{1,100}$")

object GitHubIdentifiers {
    fun validOwner(s: String?): String? =
        s?.takeIf { OWNER_RE.matches(it) }

    fun validName(s: String?): String? =
        s?.takeIf { NAME_RE.matches(it) }
}
