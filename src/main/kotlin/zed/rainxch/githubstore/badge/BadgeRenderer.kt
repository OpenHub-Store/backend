package zed.rainxch.githubstore.badge

object BadgeRenderer {

    private const val DEFAULT_HEIGHT = 30
    private const val PAD = 16
    private const val ICON_SIZE = 18
    private const val ICON_GAP = 6

    // Optical baseline for Inter-Bold at 16px in a 30px-tall pill. Tuned by
    // eye against ziadOUA's static badges so the ascender/descender sit
    // visually centered.
    private const val TEXT_BASELINE_Y = 21.0

    private fun computeBadgeWidth(text: String, hasIcon: Boolean): Int {
        val iconPart = if (hasIcon) ICON_SIZE + ICON_GAP else 0
        val textWidth = BadgeGlyphs.textWidth(text)
        return PAD + iconPart + textWidth.toInt() + PAD + 1
    }

    fun render(
        text: String,
        iconPath: String?,
        styleIndex: Int,
        variant: BadgeVariant,
        height: Int = DEFAULT_HEIGHT,
    ): String {
        val palette = BadgeColors.pick(styleIndex, variant)
        val width = computeBadgeWidth(text, hasIcon = iconPath != null)
        val radius = height / 2.0

        val iconOffset = if (iconPath != null) PAD else 0
        val textOffset = if (iconPath != null) PAD + ICON_SIZE + ICON_GAP else PAD
        val iconScale = ICON_SIZE / 24.0
        val iconY = (height - ICON_SIZE) / 2.0

        val rectSvg = """<rect width="$width" height="$height" rx="$radius" fill="${palette.background}"/>"""

        val iconSvg = if (iconPath != null) {
            """<g transform="translate($iconOffset,$iconY) scale($iconScale)"><path d="$iconPath" fill="${palette.foreground}"/></g>"""
        } else ""

        // Each character becomes its own <path>, translated by the cumulative
        // advance width. The result renders identically in every browser /
        // markdown viewer because there's no font dependency at render time.
        val glyphSvg = StringBuilder()
        var x = textOffset.toDouble()
        for (ch in text) {
            val g = BadgeGlyphs.glyph(ch)
            if (g.path.isNotEmpty()) {
                glyphSvg.append("""<path transform="translate(${fmt(x)},$TEXT_BASELINE_Y)" d="${g.path}" fill="${palette.foreground}"/>""")
            }
            x += g.advance
        }

        val safeAria = escapeXml(text)
        return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" role="img" aria-label="$safeAria">$rectSvg$iconSvg$glyphSvg</svg>"""
    }

    private fun fmt(d: Double): String {
        val rounded = (d * 100).toLong() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else "%.2f".format(rounded)
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
