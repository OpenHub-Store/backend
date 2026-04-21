package zed.rainxch.githubstore.db

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchMissCanonicalizeTest {

    private fun canon(s: String) = SearchMissRepository.canonicalize(s)

    @Test
    fun `case differences collapse`() {
        assertEquals(canon("Obsidian"), canon("obsidian"))
        assertEquals(canon("OBSIDIAN"), canon("obsidian"))
    }

    @Test
    fun `hyphen and underscore treat as space`() {
        // "read-later" and "read later" and "read_later" all map to "read later".
        assertEquals(canon("read-later"), canon("read later"))
        assertEquals(canon("read_later"), canon("read later"))
        assertEquals("read later", canon("read-later"))
    }

    @Test
    fun `multiple whitespace collapses to single space`() {
        assertEquals("foo bar baz", canon("foo   bar\t\tbaz"))
    }

    @Test
    fun `leading and trailing whitespace stripped`() {
        assertEquals("foo", canon("   foo   "))
    }

    @Test
    fun `punctuation is dropped entirely not replaced with space`() {
        assertEquals("rss feed", canon("rss! feed?"))
        assertEquals("hello world", canon("hello, world."))
        // Dots between letters collapse the word to a single token: "a.b.c" → "abc".
        // Only -, _, and whitespace become separators.
        assertEquals("abc", canon("a.b.c"))
    }

    @Test
    fun `unicode normalization folds compatible forms`() {
        // Half-width → full-width digits (NFKC target).
        val halfWidth = "FF1"
        val fullWidth = "\uFF26\uFF26\uFF11" // full-width F F 1
        // NFKC normalizes fullWidth to halfWidth, then lowercases.
        assertEquals(canon(halfWidth), canon(fullWidth))
    }

    @Test
    fun `digits are preserved`() {
        assertEquals("app 42", canon("App 42"))
    }

    @Test
    fun `empty or whitespace-only inputs return empty`() {
        assertEquals("", canon(""))
        assertEquals("", canon("   "))
        assertEquals("", canon("---"))
        assertEquals("", canon("!!!"))
    }

    @Test
    fun `non-latin letters pass through lowercased`() {
        // Cyrillic letters are Character.isLetter true, preserved, NFKC-normalized.
        assertEquals("тест", canon("ТЕСТ"))
    }
}
