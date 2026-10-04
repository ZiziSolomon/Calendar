package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule

/**
 * Pure conversions behind the rules UI, kept apart from Android so they can be unit tested.
 * MATCH_DURATION_OVER stores minutes in `pattern`; the editor talks to people in hours.
 */
object ContextualRuleText {
    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

    /** "1.5" or "1,5" hours -> "90". Null for anything that isn't a positive number. */
    fun hoursInputToPattern(input: String): String? {
        val hours = input.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val minutes = Math.round(hours * MINUTES_PER_HOUR)
        return if (hours.isFinite() && minutes > 0) minutes.toString() else null
    }

    /** "90" -> "1.5", "1440" -> "24", for pre-filling the editor. */
    fun patternToHoursInput(pattern: String): String {
        val minutes = pattern.trim().toLongOrNull() ?: return ""
        return if (minutes % MINUTES_PER_HOUR == 0L) {
            (minutes / MINUTES_PER_HOUR).toString()
        } else {
            (minutes / MINUTES_PER_HOUR.toDouble()).toString()
        }
    }

    /**
     * The editor's starting point for "Make a rule from this title": *title contains* the event's
     * title, limited to its calendar. Limiting is the cautious default, since a common word
     * ("Call") would otherwise sweep up every calendar; the user can widen it in the editor,
     * where the live preview shows the reach. Null when there's no title to match.
     */
    fun ruleFromTitle(title: String, calendarId: Long?): ContextualRule? {
        // inner runs of whitespace are kept: "contains" matches them literally
        val pattern = title.trim()
        return if (pattern.isEmpty()) null else ContextualRule(id = null, calendarId = calendarId, matchType = MATCH_TITLE_CONTAINS, pattern = pattern)
    }

    enum class Unit { DAYS, HOURS, MINUTES }

    /** Largest whole unit for display: 2880 -> 2 days, 90 -> 90 minutes, 120 -> 2 hours. */
    fun durationParts(minutes: Long): Pair<Long, Unit> = when {
        minutes > 0 && minutes % MINUTES_PER_DAY == 0L -> Pair(minutes / MINUTES_PER_DAY, Unit.DAYS)
        minutes > 0 && minutes % MINUTES_PER_HOUR == 0L -> Pair(minutes / MINUTES_PER_HOUR, Unit.HOURS)
        else -> Pair(minutes, Unit.MINUTES)
    }
}
