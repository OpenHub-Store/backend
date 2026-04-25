package zed.rainxch.githubstore.badge

import java.io.File
import kotlin.test.Test

class BadgePreviewDump {

    @Test
    fun `dump readme badge previews to build directory`() {
        val outDir = File("build/badge-previews").apply { mkdirs() }

        val gallery = StringBuilder()
        gallery.append("<!doctype html><meta charset=\"utf-8\"><title>M3 badge previews</title>")
        gallery.append("<style>")
        gallery.append("body{font-family:system-ui;background:#fff;color:#111;padding:24px}")
        gallery.append(".dark{background:#1a1a1a;color:#fff;padding:24px;margin-top:32px;border-radius:12px}")
        gallery.append(".grid{display:grid;grid-template-columns:repeat(3,max-content);gap:8px 16px;margin:8px 0 24px}")
        gallery.append(".label{font-size:12px;opacity:.7;margin:8px 0 4px}")
        gallery.append("h2{font-size:14px;font-weight:600;margin:24px 0 4px}")
        gallery.append("</style>")

        val hueNames = listOf("Red", "Orange", "Yellow", "Lime", "Green", "Teal", "Indigo", "Blue", "Purple", "Pink", "Magenta", "Neutral")

        gallery.append("<h1>Full palette (12 hues × 3 variants)</h1>")
        gallery.append("<p style='opacity:.7'>Variant 1 = dark/saturated · Variant 2 = medium · Variant 3 = pastel</p>")
        gallery.append("<div class=grid>")
        for (s in 1..12) {
            for (vIdx in 1..3) {
                val variant = BadgeVariant.fromIndex(vIdx)!!
                val svg = BadgeRenderer.render("Sample $s/$vIdx", BadgeIcons.STAR, s, variant)
                File(outDir, "palette_${s}_${vIdx}.svg").writeText(svg)
                gallery.append(svg)
            }
        }
        gallery.append("</div>")

        // Concrete README badges with realistic sample text. The live endpoints
        // pull stars/release/downloads from Postgres (with GitHub API fallback
        // for missing release tags).
        data class Sample(val name: String, val text: String, val icon: String?, val style: Int, val variant: Int)
        val readmeSamples = listOf(
            Sample("downloads", "200K", BadgeIcons.DOWNLOAD, 5, 2),
            Sample("stars", "12K stars", BadgeIcons.STAR, 3, 1),
            Sample("users", "65K+ Users", BadgeIcons.GROUPS, 8, 2),
            Sample("release", "v1.7.0", BadgeIcons.PACKAGE, 9, 1),
            Sample("fdroid", "F-Droid v1.7.0", BadgeIcons.FDROID, 5, 1),
            Sample("static_kotlin_mp", "Kotlin Multiplatform", BadgeIcons.CODE, 10, 1),
            Sample("static_compose_mp", "Compose Multiplatform", BadgeIcons.WIDGETS, 8, 1),
            Sample("static_material_you", "Material You", BadgeIcons.PALETTE, 6, 1),
            Sample("static_api_24", "API 24+", BadgeIcons.ANDROID, 5, 1),
        )

        gallery.append("<h2>README badges (light page)</h2><div class=grid>")
        for (s in readmeSamples) {
            val svg = BadgeRenderer.render(s.text, s.icon, s.style, BadgeVariant.fromIndex(s.variant)!!)
            File(outDir, "${s.name}.svg").writeText(svg)
            gallery.append("<code>${s.name}</code><span></span><span></span>")
            gallery.append(svg)
            gallery.append("<span></span><span></span>")
        }
        gallery.append("</div>")

        gallery.append("<div class=dark><h2>README badges on a dark page</h2><div class=grid>")
        for (s in readmeSamples) {
            val svg = BadgeRenderer.render(s.text, s.icon, s.style, BadgeVariant.fromIndex(s.variant)!!)
            gallery.append("<code>${s.name}</code><span></span><span></span>")
            gallery.append(svg)
            gallery.append("<span></span><span></span>")
        }
        gallery.append("</div></div>")

        gallery.append("<h2>Reference: ziadOUA Android badges (variant 1, 2, 3)</h2><div class=grid>")
        for (vIdx in 1..3) {
            val svg = BadgeRenderer.render("Android", BadgeIcons.ANDROID, 5, BadgeVariant.fromIndex(vIdx)!!)
            gallery.append(svg)
        }
        gallery.append("</div>")

        gallery.append("<h2>Hue legend</h2><div class=grid>")
        hueNames.forEachIndexed { i, label ->
            val svg = BadgeRenderer.render(label, null, i + 1, BadgeVariant.Dark)
            gallery.append(svg)
        }
        gallery.append("</div>")

        File(outDir, "gallery.html").writeText(gallery.toString())
    }
}
