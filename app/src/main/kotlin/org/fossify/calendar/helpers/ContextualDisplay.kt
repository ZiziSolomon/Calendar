package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule

/**
 * Which rule decides how a context looks, when several match. Zizi's model (Phase 16): mark a
 * whole calendar as contextual, then carve pieces out of it with more specific rules, so the
 * most specific rule with a value wins. Name and colour resolve independently: a calendar rule
 * can set the colour while a title rule inside it sets the name. Pure, so it's unit tested.
 */
object ContextualDisplay {
    data class Result(val keyName: String?, val keyColor: Int?)

    val NONE = Result(null, null)

    /** Lower is more specific. A calendar-scoped rule beats the same kind of rule on every calendar. */
    fun specificity(rule: ContextualRule): Int {
        val kind = when (rule.matchType) {
            MATCH_EVENT_ID -> 0
            MATCH_TITLE_CONTAINS, MATCH_TITLE_REGEX -> 1
            MATCH_DURATION_OVER -> 2
            else -> 3   // MATCH_ALL: a whole calendar
        }
        return kind * 2 + if (rule.calendarId == null) 1 else 0
    }

    /** [matching] in rule order; ties in specificity go to the earlier rule. */
    fun resolve(matching: List<ContextualRule>): Result {
        if (matching.isEmpty()) {
            return NONE
        }

        val ranked = matching.withIndex()
            .sortedWith(compareBy({ specificity(it.value) }, { it.index }))
            .map { it.value }
        return Result(
            keyName = ranked.firstNotNullOfOrNull { rule -> rule.keyName?.trim()?.takeIf { it.isNotEmpty() } },
            keyColor = ranked.firstNotNullOfOrNull { it.keyColor },
        )
    }
}
