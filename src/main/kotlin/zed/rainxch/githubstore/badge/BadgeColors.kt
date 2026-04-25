package zed.rainxch.githubstore.badge

// Hex values transcribed from ziadOUA/m3-Markdown-Badges
// (dynamicBadges/main.py — variant_1 / variant_2 / variant_3 +
// on_variant_1..3). 12 hues × 3 shade variants. Order:
//   1 red, 2 orange, 3 yellow, 4 lime, 5 green, 6 teal,
//   7 indigo, 8 blue, 9 purple, 10 pink, 11 magenta, 12 neutral.
object BadgeColors {

    // Variant 1: dark/saturated background, white foreground.
    private val variant1Bg = listOf(
        "#B02E26", "#974800", "#8E7500", "#3F6900", "#4B6700", "#00696A",
        "#4B53B9", "#006493", "#8A33B8", "#9E2A99", "#99405E", "#1B1B1B",
    )
    private const val variant1Fg = "#FFFFFF"

    // Variant 2: medium-tone background, hue-matched dark foreground.
    private val variant2Bg = listOf(
        "#FFB4AA", "#FFB689", "#E9C327", "#92DA35", "#AFD364", "#4CDADB",
        "#BEC2FF", "#8DCDFF", "#E8B3FF", "#FFABF1", "#FFB1C5", "#C6C6C6",
    )
    private val variant2Fg = listOf(
        "#690004", "#512400", "#3B2F00", "#1F3700", "#253600", "#003737",
        "#181F89", "#00344F", "#500075", "#5C005A", "#5E1130", "#303030",
    )

    // Variant 3: pastel background, hue-matched dark foreground.
    private val variant3Bg = listOf(
        "#FFDAD5", "#FFDBC7", "#FFE177", "#ACF850", "#CAF07D", "#6FF6F7",
        "#E0E0FF", "#CAE6FF", "#F6D9FF", "#FFD7F4", "#FFD9E1", "#E2E2E2",
    )
    private val variant3Fg = listOf(
        "#410001", "#311300", "#231B00", "#102000", "#141F00", "#002020",
        "#00036B", "#001E30", "#310049", "#380037", "#3F001B", "#1B1B1B",
    )

    fun pick(styleIndex: Int, variant: BadgeVariant): BadgePalette {
        require(styleIndex in 1..12) { "style must be 1..12, got $styleIndex" }
        val i = styleIndex - 1
        return when (variant) {
            BadgeVariant.Dark -> BadgePalette(variant1Bg[i], variant1Fg)
            BadgeVariant.Medium -> BadgePalette(variant2Bg[i], variant2Fg[i])
            BadgeVariant.Light -> BadgePalette(variant3Bg[i], variant3Fg[i])
        }
    }
}
