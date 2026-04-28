package zed.rainxch.githubstore.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GithubSourceUrlTest {

    @Test
    fun `bare canonical URL parses`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("https://github.com/octocat/hello-world"))
    }

    @Test
    fun `trailing slash is tolerated`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("https://github.com/octocat/hello-world/"))
    }

    @Test
    fun `_ git suffix is stripped`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("https://github.com/octocat/hello-world.git"))
    }

    @Test
    fun `deep path is ignored`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("https://github.com/octocat/hello-world/tree/main/src/foo"))
    }

    @Test
    fun `query string is ignored`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("https://github.com/octocat/hello-world?ref=main"))
    }

    @Test
    fun `fragment is ignored`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("https://github.com/octocat/hello-world#readme"))
    }

    @Test
    fun `mixed-case owner like OpenHub-Store survives`() {
        assertEquals("OpenHub-Store" to "GitHub-Store", GithubSourceUrl.parse("https://github.com/OpenHub-Store/GitHub-Store"))
    }

    @Test
    fun `dots in repo name like F-Droid_Repository allowed`() {
        assertEquals("foo" to "F.Droid_Repository", GithubSourceUrl.parse("https://github.com/foo/F.Droid_Repository"))
    }

    @Test
    fun `http scheme is accepted`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("http://github.com/octocat/hello-world"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("octocat" to "hello-world", GithubSourceUrl.parse("  https://github.com/octocat/hello-world  "))
    }

    @Test
    fun `non-github hosts are rejected`() {
        assertNull(GithubSourceUrl.parse("https://gitlab.com/octocat/hello-world"))
        assertNull(GithubSourceUrl.parse("https://codeberg.org/octocat/hello-world"))
        assertNull(GithubSourceUrl.parse("https://raw.githubusercontent.com/octocat/hello-world/main/README.md"))
        assertNull(GithubSourceUrl.parse("https://gist.github.com/octocat/abc"))
    }

    @Test
    fun `missing repo segment is rejected`() {
        assertNull(GithubSourceUrl.parse("https://github.com/octocat"))
        assertNull(GithubSourceUrl.parse("https://github.com/octocat/"))
    }

    @Test
    fun `empty or garbage is rejected`() {
        assertNull(GithubSourceUrl.parse(""))
        assertNull(GithubSourceUrl.parse("not a url"))
        assertNull(GithubSourceUrl.parse("https://"))
    }

    @Test
    fun `owner starting with hyphen is rejected per GitHub rules`() {
        assertNull(GithubSourceUrl.parse("https://github.com/-bad/repo"))
    }

    @Test
    fun `owner exceeding 39 chars is rejected`() {
        val tooLong = "a".repeat(40)
        assertNull(GithubSourceUrl.parse("https://github.com/$tooLong/repo"))
    }

    @Test
    fun `enterprise github is not matched`() {
        // Self-hosted GHE instances would have different hosts; we only seed
        // github.com source URLs to keep the matcher predictable.
        assertNull(GithubSourceUrl.parse("https://github.example.com/owner/repo"))
    }
}
