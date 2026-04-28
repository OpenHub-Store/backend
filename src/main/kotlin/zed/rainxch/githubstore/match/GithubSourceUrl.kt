package zed.rainxch.githubstore.match

// Parses an F-Droid sourceCode URL into (owner, repo) iff it points at github.com.
// Used by FdroidSeedWorker to filter the F-Droid index down to GitHub-hosted apps.
//
// Accepted shapes:
//   https://github.com/owner/repo
//   https://github.com/owner/repo/
//   https://github.com/owner/repo.git
//   https://github.com/owner/repo/tree/main/path
//   http://github.com/owner/repo            (rare in F-Droid but harmless)
//
// Rejects: gitlab/codeberg/etc, github raw or gist subdomains, missing repo.
object GithubSourceUrl {

    // Owner: GitHub's actual username rule (^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$),
    // Repo: a slightly broader practical set up to 100 chars. Matches the
    // identifier validation used elsewhere in the backend.
    private val RE = Regex(
        "^https?://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})(?:\\.git)?(?:[/?#].*)?$",
    )

    fun parse(url: String): Pair<String, String>? {
        val trimmed = url.trim()
        val match = RE.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return owner to repo
    }
}
