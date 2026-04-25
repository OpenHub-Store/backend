package zed.rainxch.githubstore.badge

object BadgeRenderer {

    private const val DEFAULT_HEIGHT = 30
    private const val PAD = 15
    private const val ICON_SIZE = 18
    private const val ICON_GAP = 6
    private const val FONT_SIZE = 14
    private const val DEFAULT_CHAR_WIDTH = 8.5

    // Per-character widths approximating Roboto-style bold rendering at 14px.
    // Tuned so the resulting badge widths land near ziadOUA's hand-laid out
    // /badges/*.svg (e.g. "Android" at ~138px including 18px icon + gap).
    private val charWidth: Map<Char, Double> = buildMap {
        " .,:;!|".forEach { put(it, 4.5) }
        "iIlrt".forEach { put(it, 5.5) }
        "0123456789".forEach { put(it, 8.0) }
        "MWmw".forEach { put(it, 12.0) }
        "-/".forEach { put(it, 6.5) }
    }

    private fun estimateTextWidthPx(text: String): Int =
        text.sumOf { charWidth[it] ?: DEFAULT_CHAR_WIDTH }.toInt() + 1

    private fun computeBadgeWidth(text: String, hasIcon: Boolean): Int {
        val iconPart = if (hasIcon) ICON_SIZE + ICON_GAP else 0
        return PAD + iconPart + estimateTextWidthPx(text) + PAD
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
        // visual baseline — Roboto-ish optical centering at 14px
        val textY = (height + FONT_SIZE) / 2 - 1
        val iconScale = ICON_SIZE / 24.0
        val iconY = (height - ICON_SIZE) / 2.0

        val rectSvg = """<rect width="$width" height="$height" rx="$radius" fill="${palette.background}"/>"""

        val iconSvg = if (iconPath != null) {
            """<g transform="translate($iconOffset,$iconY) scale($iconScale)"><path d="$iconPath" fill="${palette.foreground}"/></g>"""
        } else ""

        val safeText = escapeXml(text)

        return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" role="img" aria-label="$safeText">$rectSvg$iconSvg<text x="$textOffset" y="$textY" font-family="'Roboto','Outfit',system-ui,-apple-system,'Segoe UI',sans-serif" font-size="$FONT_SIZE" font-weight="700" fill="${palette.foreground}">$safeText</text></svg>"""
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
