package zed.rainxch.githubstore.badge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BadgeRendererTest {

    @Test
    fun `humanize count formats thousands and millions`() {
        assertEquals("0", BadgeService.humanizeCount(0))
        assertEquals("999", BadgeService.humanizeCount(999))
        assertEquals("1K", BadgeService.humanizeCount(1_000))
        assertEquals("1.2K", BadgeService.humanizeCount(1_234))
        assertEquals("65K", BadgeService.humanizeCount(65_000))
        assertEquals("1M", BadgeService.humanizeCount(1_000_000))
        assertEquals("1.2M", BadgeService.humanizeCount(1_234_567))
    }

    @Test
    fun `every style and variant combination produces valid svg`() {
        for (s in 1..12) {
            for (vIdx in 1..3) {
                val variant = BadgeVariant.fromIndex(vIdx)!!
                val svg = BadgeRenderer.render(
                    text = "65K",
                    iconPath = BadgeIcons.DOWNLOAD,
                    styleIndex = s,
                    variant = variant,
                )
                assertTrue(svg.startsWith("<svg "), "style=$s variant=$vIdx")
                assertTrue(svg.endsWith("</svg>"), "style=$s variant=$vIdx")
                assertTrue(svg.contains("65K"), "style=$s variant=$vIdx")
                assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
            }
        }
    }

    @Test
    fun `variant 1 uses saturated background and white foreground`() {
        // Style 5 (green) variant 1 → background #4B6700, foreground #FFFFFF
        val svg = BadgeRenderer.render("Android", BadgeIcons.ANDROID, 5, BadgeVariant.Dark)
        assertTrue(svg.contains("#4B6700"), "expected dark green background")
        assertTrue(svg.contains("#FFFFFF"), "expected white foreground")
    }

    @Test
    fun `variant 3 uses pastel background and per-hue dark foreground`() {
        // Style 5 (green) variant 3 → background #CAF07D, foreground #141F00
        val svg = BadgeRenderer.render("Android", BadgeIcons.ANDROID, 5, BadgeVariant.Light)
        assertTrue(svg.contains("#CAF07D"))
        assertTrue(svg.contains("#141F00"))
    }

    @Test
    fun `xml special characters in label are escaped`() {
        val svg = BadgeRenderer.render(
            text = "<script>&\"'",
            iconPath = null,
            styleIndex = 1,
            variant = BadgeVariant.Dark,
        )
        assertFalse(svg.contains("<script>"), "raw < should not appear inside text node")
        assertTrue(svg.contains("&lt;script&gt;"))
        assertTrue(svg.contains("&amp;"))
    }

    @Test
    fun `bad style index throws`() {
        try {
            BadgeRenderer.render("x", null, styleIndex = 0, variant = BadgeVariant.Dark)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            BadgeRenderer.render("x", null, styleIndex = 13, variant = BadgeVariant.Dark)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
