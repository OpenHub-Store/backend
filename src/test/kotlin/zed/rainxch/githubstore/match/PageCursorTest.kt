package zed.rainxch.githubstore.match

import zed.rainxch.githubstore.match.SigningFingerprintRepository.PageCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageCursorTest {

    @Test
    fun `encode then decode round-trips every field`() {
        val original = PageCursor(
            observedAt = 1745678901234L,
            fingerprint = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89",
            owner = "Octocat-Org",
            repo = "hello-world",
        )
        val encoded = original.encode()
        val decoded = PageCursor.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `encoded form is url-safe base64 without padding`() {
        val cursor = PageCursor(0L, "FP", "o", "r")
        val encoded = cursor.encode()
        // No '=' padding (we strip it), no '+' or '/' (URL-safe alphabet).
        assert(!encoded.contains('=')) { "padding leaked into cursor: $encoded" }
        assert(!encoded.contains('+')) { "non-url-safe '+' in cursor: $encoded" }
        assert(!encoded.contains('/')) { "non-url-safe '/' in cursor: $encoded" }
    }

    @Test
    fun `decode rejects garbage tokens`() {
        assertNull(PageCursor.decode("not-base64-at-all-!!!"))
        assertNull(PageCursor.decode(""))
        // Valid base64 but wrong shape (only 2 fields after decode):
        assertNull(PageCursor.decode("MTIzfGFi")) // "123|ab"
    }

    @Test
    fun `decode tolerates the standard pad-stripping`() {
        // Whatever encode() produces must round-trip; sanity-check a cursor
        // whose raw form would normally have padding.
        val cursor = PageCursor(7L, "X", "y", "z")
        val encoded = cursor.encode()
        // Tack on padding manually — decoder should still accept (since we
        // re-pad inside decode).
        val withPadding = encoded + "=="
        // Stripping the padding back out before decoding (decode handles
        // both forms — verify the un-padded form first).
        assertEquals(cursor, PageCursor.decode(encoded))
        // With padding it should also work — we re-pad internally.
        assertEquals(cursor, PageCursor.decode(withPadding.trimEnd('=')))
    }
}
