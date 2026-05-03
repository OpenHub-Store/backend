package zed.rainxch.githubstore.announcements

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementValidatorTest {

    private fun valid(
        id: String = "2026-06-15-test",
        publishedAt: String = "2026-06-15T00:00:00Z",
        expiresAt: String? = null,
        severity: String = "INFO",
        category: String = "NEWS",
        title: String = "Test announcement",
        body: String = "B".repeat(60),
        ctaUrl: String? = null,
        ctaLabel: String? = null,
        dismissible: Boolean = true,
        requiresAcknowledgment: Boolean = false,
        i18n: Map<String, AnnouncementLocaleDto> = emptyMap(),
        iconHint: String? = null,
    ) = AnnouncementDto(
        id = id,
        publishedAt = publishedAt,
        expiresAt = expiresAt,
        severity = severity,
        category = category,
        title = title,
        body = body,
        ctaUrl = ctaUrl,
        ctaLabel = ctaLabel,
        dismissible = dismissible,
        requiresAcknowledgment = requiresAcknowledgment,
        i18n = i18n,
        iconHint = iconHint,
    )

    @Test
    fun `valid item passes`() {
        assertEquals(emptyList(), AnnouncementValidator.validate(valid()))
    }

    @Test
    fun `id blank or too long fails`() {
        assertTrue(AnnouncementValidator.validate(valid(id = "")).any { it.startsWith("id:") })
        assertTrue(AnnouncementValidator.validate(valid(id = "x".repeat(65))).any { it.startsWith("id:") })
    }

    @Test
    fun `bad ISO dates fail`() {
        assertTrue(AnnouncementValidator.validate(valid(publishedAt = "yesterday")).any { it.startsWith("publishedAt:") })
        assertTrue(
            AnnouncementValidator.validate(valid(expiresAt = "2026/01/01")).any { it.startsWith("expiresAt:") },
        )
    }

    @Test
    fun `unknown enum values fail`() {
        assertTrue(AnnouncementValidator.validate(valid(severity = "PANIC")).any { it.startsWith("severity:") })
        assertTrue(AnnouncementValidator.validate(valid(category = "ADVERTISING")).any { it.startsWith("category:") })
        assertTrue(
            AnnouncementValidator.validate(valid(iconHint = "FIRE")).any { it.startsWith("iconHint:") },
        )
    }

    @Test
    fun `enum match is case insensitive`() {
        // "info"/"news"/"info" are accepted -- spec allows case-insensitive
        // enum strings, client lowercases anyway.
        assertEquals(
            emptyList(),
            AnnouncementValidator.validate(valid(severity = "info", category = "news", iconHint = "info")),
        )
    }

    @Test
    fun `title length capped at 80`() {
        assertTrue(AnnouncementValidator.validate(valid(title = "x".repeat(81))).any { it.contains("title:") })
    }

    @Test
    fun `body length window 50 to 600`() {
        assertTrue(AnnouncementValidator.validate(valid(body = "short")).any { it.contains("body:") })
        assertTrue(AnnouncementValidator.validate(valid(body = "x".repeat(601))).any { it.contains("body:") })
    }

    @Test
    fun `acknowledge requires dismissible false`() {
        val errs = AnnouncementValidator.validate(
            valid(requiresAcknowledgment = true, dismissible = true),
        )
        assertTrue(errs.any { it.contains("requiresAcknowledgment") })
    }

    @Test
    fun `security category requires elevated severity`() {
        val errs = AnnouncementValidator.validate(valid(category = "SECURITY", severity = "INFO"))
        assertTrue(errs.any { it.contains("category=SECURITY") })

        // IMPORTANT and CRITICAL pass.
        assertEquals(
            emptyList(),
            AnnouncementValidator.validate(
                valid(category = "SECURITY", severity = "CRITICAL", dismissible = true),
            ),
        )
    }

    @Test
    fun `privacy category requires acknowledgment`() {
        val errs = AnnouncementValidator.validate(valid(category = "PRIVACY"))
        assertTrue(errs.any { it.contains("category=PRIVACY") })

        // With acknowledgment + non-dismissible: passes.
        assertEquals(
            emptyList(),
            AnnouncementValidator.validate(
                valid(category = "PRIVACY", requiresAcknowledgment = true, dismissible = false),
            ),
        )
    }

    @Test
    fun `ctaUrl must be https`() {
        assertTrue(
            AnnouncementValidator.validate(valid(ctaUrl = "http://example.com"))
                .any { it.startsWith("ctaUrl:") },
        )
        assertTrue(
            AnnouncementValidator.validate(valid(ctaUrl = "ftp://example.com"))
                .any { it.startsWith("ctaUrl:") },
        )
    }

    @Test
    fun `i18n locale code must be BCP-47`() {
        val errs = AnnouncementValidator.validate(
            valid(i18n = mapOf("en_US" to AnnouncementLocaleDto(title = "x", body = "y".repeat(60)))),
        )
        assertTrue(errs.any { it.contains("i18n.en_US:") })
    }

    @Test
    fun `i18n variant lengths checked independently`() {
        // English body in budget, Japanese body too short -- only the JA error
        // should surface.
        val errs = AnnouncementValidator.validate(
            valid(i18n = mapOf("ja" to AnnouncementLocaleDto(body = "short"))),
        )
        assertTrue(errs.any { it.contains("i18n.ja.body") })
    }

    @Test
    fun `i18n cta url must be https`() {
        val errs = AnnouncementValidator.validate(
            valid(
                i18n = mapOf(
                    "zh-CN" to AnnouncementLocaleDto(ctaUrl = "http://example.cn"),
                ),
            ),
        )
        assertTrue(errs.any { it.contains("i18n.zh-CN.ctaUrl:") })
    }

    @Test
    fun `duplicate ids detected at top level`() {
        val items = listOf(valid(id = "a"), valid(id = "b"), valid(id = "a"))
        val err = AnnouncementValidator.checkDuplicates(items)
        assertTrue(err != null && err.contains("a"))
    }

    @Test
    fun `unique ids pass duplicate check`() {
        assertNull(AnnouncementValidator.checkDuplicates(listOf(valid(id = "a"), valid(id = "b"))))
    }

    @Test
    fun `id with path traversal rejected`() {
        assertTrue(AnnouncementValidator.validate(valid(id = "../etc")).any { it.startsWith("id:") })
    }

    @Test
    fun `id with mixed case rejected`() {
        assertTrue(AnnouncementValidator.validate(valid(id = "Has-Caps")).any { it.startsWith("id:") })
    }

    @Test
    fun `id with slash rejected`() {
        assertTrue(AnnouncementValidator.validate(valid(id = "path/traversal")).any { it.startsWith("id:") })
    }

    @Test
    fun `id with space rejected`() {
        assertTrue(AnnouncementValidator.validate(valid(id = "with space")).any { it.startsWith("id:") })
    }

    @Test
    fun `well-formed id accepted`() {
        assertEquals(emptyList(), AnnouncementValidator.validate(valid(id = "valid-id-123")))
    }

    @Test
    fun `title with newline rejected`() {
        val errs = AnnouncementValidator.validate(valid(title = "line1\nline2"))
        assertTrue(errs.any { it.contains("title:") && it.contains("control") })
    }

    @Test
    fun `body with rtl override rejected`() {
        val errs = AnnouncementValidator.validate(valid(body = "B".repeat(60) + "‮"))
        assertTrue(errs.any { it.contains("body:") && it.contains("bidi") })
    }

    @Test
    fun `body with newline accepted`() {
        // Body is multi-line; \n / \r / \t are allowed because the admin panel
        // surfaces a textarea and the client renders body as plain text with
        // newlines preserved.
        assertEquals(
            emptyList(),
            AnnouncementValidator.validate(valid(body = "B".repeat(30) + "\nline2 " + "B".repeat(20))),
        )
    }

    @Test
    fun `body with tab and carriage return accepted`() {
        assertEquals(
            emptyList(),
            AnnouncementValidator.validate(valid(body = "B".repeat(30) + "\t\r\n" + "B".repeat(20))),
        )
    }

    @Test
    fun `body with NULL char still rejected`() {
        val errs = AnnouncementValidator.validate(valid(body = "B".repeat(30) + " " + "B".repeat(30)))
        assertTrue(errs.any { it.contains("body:") && it.contains("control") })
    }

    @Test
    fun `per-locale title with bidi override rejected`() {
        val errs = AnnouncementValidator.validate(
            valid(i18n = mapOf("ar" to AnnouncementLocaleDto(title = "hello‪"))),
        )
        assertTrue(errs.any { it.contains("i18n.ar.title") && it.contains("bidi") })
    }

    @Test
    fun `ctaLabel too long rejected`() {
        val errs = AnnouncementValidator.validate(valid(ctaLabel = "x".repeat(31)))
        assertTrue(errs.any { it.startsWith("ctaLabel:") })
    }

    @Test
    fun `ctaLabel with control char rejected`() {
        val errs = AnnouncementValidator.validate(valid(ctaLabel = "click\tme"))
        assertTrue(errs.any { it.startsWith("ctaLabel:") && it.contains("control") })
    }

    @Test
    fun `per-locale ctaLabel with bidi codepoint rejected`() {
        val errs = AnnouncementValidator.validate(
            valid(i18n = mapOf("he" to AnnouncementLocaleDto(ctaLabel = "open⁦"))),
        )
        assertTrue(errs.any { it.contains("i18n.he.ctaLabel") && it.contains("bidi") })
    }

    @Test
    fun `expiresAt before publishedAt rejected`() {
        val errs = AnnouncementValidator.validate(
            valid(publishedAt = "2026-06-15T00:00:00Z", expiresAt = "2026-06-14T00:00:00Z"),
        )
        assertTrue(errs.any { it.startsWith("expiresAt:") && it.contains("must be after publishedAt") })
    }

    @Test
    fun `expiresAt equal to publishedAt rejected`() {
        val errs = AnnouncementValidator.validate(
            valid(publishedAt = "2026-06-15T00:00:00Z", expiresAt = "2026-06-15T00:00:00Z"),
        )
        assertTrue(errs.any { it.startsWith("expiresAt:") && it.contains("must be after publishedAt") })
    }

    @Test
    fun `expiresAt after publishedAt accepted`() {
        assertEquals(
            emptyList(),
            AnnouncementValidator.validate(
                valid(publishedAt = "2026-06-15T00:00:00Z", expiresAt = "2026-06-16T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `null expiresAt accepted`() {
        assertEquals(emptyList(), AnnouncementValidator.validate(valid(expiresAt = null)))
    }
}
