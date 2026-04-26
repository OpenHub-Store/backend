package zed.rainxch.githubstore.badge

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.PathIterator

// Pre-vectorizes the printable-ASCII range of Inter-Bold at the badge's font
// size into SVG path strings + advance widths. Render time becomes "look up
// each char, translate the path, emit". This sidesteps the font-availability
// problem with `<text>` rendering and matches ziadOUA/m3-Markdown-Badges's
// approach of shipping pre-rendered glyph paths.
//
// Inter-Bold (SIL OFL 1.1) is bundled in src/main/resources/fonts/.
object BadgeGlyphs {

    data class Glyph(val path: String, val advance: Double)

    const val FONT_SIZE_PX = 16f

    private val font: Font by lazy {
        val stream = this::class.java.classLoader.getResourceAsStream("fonts/Inter-Bold.ttf")
            ?: error("fonts/Inter-Bold.ttf not on classpath")
        stream.use { Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(FONT_SIZE_PX) }
    }

    private val frc = FontRenderContext(null, true, true)

    private val cache: Map<Char, Glyph> by lazy {
        (32..126).associate { code ->
            val ch = code.toChar()
            ch to extract(ch)
        }
    }

    fun glyph(ch: Char): Glyph = cache[ch] ?: cache[' ']!!

    fun textWidth(text: String): Double = text.sumOf { glyph(it).advance }

    private fun extract(ch: Char): Glyph {
        val gv = font.createGlyphVector(frc, ch.toString())
        val outline = gv.getOutline(0f, 0f)
        val advance = gv.getGlyphMetrics(0).advance.toDouble()

        val sb = StringBuilder()
        val coords = DoubleArray(6)
        val iter = outline.getPathIterator(null)
        while (!iter.isDone) {
            when (iter.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> sb.append("M").append(fmt(coords[0])).append(" ").append(fmt(coords[1])).append(" ")
                PathIterator.SEG_LINETO -> sb.append("L").append(fmt(coords[0])).append(" ").append(fmt(coords[1])).append(" ")
                PathIterator.SEG_QUADTO -> sb.append("Q").append(fmt(coords[0])).append(" ").append(fmt(coords[1]))
                    .append(" ").append(fmt(coords[2])).append(" ").append(fmt(coords[3])).append(" ")
                PathIterator.SEG_CUBICTO -> sb.append("C").append(fmt(coords[0])).append(" ").append(fmt(coords[1]))
                    .append(" ").append(fmt(coords[2])).append(" ").append(fmt(coords[3]))
                    .append(" ").append(fmt(coords[4])).append(" ").append(fmt(coords[5])).append(" ")
                PathIterator.SEG_CLOSE -> sb.append("Z ")
            }
            iter.next()
        }
        return Glyph(sb.toString().trim(), advance)
    }

    private fun fmt(d: Double): String {
        val rounded = (d * 100).toLong() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else "%.2f".format(rounded)
    }
}
