package zed.rainxch.githubstore.badge

// Mirrors the ziadOUA/m3-Markdown-Badges convention:
// - `style` (1..12) selects a hue (red / orange / yellow / lime / green /
//   teal / indigo / blue / purple / pink / magenta / neutral)
// - `variant` (1..3) selects a shade of that hue (dark / medium / light).
// All variants are filled — there is no outlined treatment. Theme adaption
// is the user's choice of variant, not a separate query parameter.
enum class BadgeVariant(val index: Int) {
    Dark(1),
    Medium(2),
    Light(3);

    companion object {
        fun fromIndex(i: Int): BadgeVariant? = entries.firstOrNull { it.index == i }
    }
}

data class BadgePalette(
    val background: String,
    val foreground: String,
)
