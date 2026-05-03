package zed.rainxch.githubstore.announcements

import java.time.format.DateTimeParseException
import java.time.Instant

// Server-side enforcement of every rule in announcements-endpoint.md §2.
// Runs at load time so a malformed file never reaches the served payload.
// Returns a list of violations (empty = valid). The loader logs and drops
// any item that produces a non-empty list.
object AnnouncementValidator {

    private val SEVERITIES = setOf("INFO", "IMPORTANT", "CRITICAL")
    private val CATEGORIES = setOf("NEWS", "PRIVACY", "SURVEY", "SECURITY", "STATUS")
    private val ICON_HINTS = setOf("INFO", "WARNING", "SECURITY", "CELEBRATION", "CHANGE")

    private const val ID_MAX = 64
    private const val TITLE_MAX = 80
    private const val BODY_MIN = 50
    private const val BODY_MAX = 600

    // BCP-47 -- a primary subtag of 2–3 letters, optional region/script subtags
    // of 2–8 alphanumerics each. Strict enough to reject "en_US" and "EN-us-".
    private val BCP47 = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    fun validate(item: AnnouncementDto): List<String> {
        val errs = mutableListOf<String>()

        if (item.id.isBlank()) errs += "id: blank"
        else if (item.id.length > ID_MAX) errs += "id: > $ID_MAX chars"

        if (!isIso8601(item.publishedAt)) errs += "publishedAt: not ISO 8601"
        item.expiresAt?.let { if (!isIso8601(it)) errs += "expiresAt: not ISO 8601" }

        val severity = item.severity.uppercase()
        if (severity !in SEVERITIES) errs += "severity: '${item.severity}' not in $SEVERITIES"

        val category = item.category.uppercase()
        if (category !in CATEGORIES) errs += "category: '${item.category}' not in $CATEGORIES"

        item.iconHint?.let {
            if (it.uppercase() !in ICON_HINTS) errs += "iconHint: '$it' not in $ICON_HINTS"
        }

        // Locale-aware length checks. Defaults are checked against EN; each
        // i18n variant is checked independently because translators can
        // overrun even when the source is in budget.
        validateText(label = "title", value = item.title, min = 1, max = TITLE_MAX)?.let(errs::add)
        validateText(label = "body", value = item.body, min = BODY_MIN, max = BODY_MAX)?.let(errs::add)

        item.i18n.forEach { (locale, variant) ->
            if (!BCP47.matches(locale)) errs += "i18n.$locale: not a BCP-47 code"
            variant.title?.let { validateText("i18n.$locale.title", it, 1, TITLE_MAX)?.let(errs::add) }
            variant.body?.let { validateText("i18n.$locale.body", it, BODY_MIN, BODY_MAX)?.let(errs::add) }
            variant.ctaUrl?.let { if (!it.startsWith("https://")) errs += "i18n.$locale.ctaUrl: must be https://" }
        }

        if (item.requiresAcknowledgment && item.dismissible) {
            errs += "requiresAcknowledgment=true requires dismissible=false"
        }
        if (category == "SECURITY" && severity !in setOf("IMPORTANT", "CRITICAL")) {
            errs += "category=SECURITY requires severity in [IMPORTANT, CRITICAL]"
        }
        if (category == "PRIVACY" && !item.requiresAcknowledgment) {
            errs += "category=PRIVACY requires requiresAcknowledgment=true"
        }

        item.ctaUrl?.let {
            if (!it.startsWith("https://")) errs += "ctaUrl: must be https://"
        }

        return errs
    }

    // Top-level validator used after every individual item has passed. Catches
    // the cross-item rule that no two items may share an id; per spec, a
    // duplicate rejects the whole payload, not just the duplicate.
    fun checkDuplicates(items: List<AnnouncementDto>): String? {
        val dupes = items.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        return if (dupes.isEmpty()) null
        else "duplicate ids: $dupes"
    }

    private fun validateText(label: String, value: String, min: Int, max: Int): String? {
        val len = value.length
        return when {
            len < min -> "$label: < $min chars (was $len)"
            len > max -> "$label: > $max chars (was $len)"
            else -> null
        }
    }

    private fun isIso8601(value: String): Boolean = try {
        Instant.parse(value)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}
