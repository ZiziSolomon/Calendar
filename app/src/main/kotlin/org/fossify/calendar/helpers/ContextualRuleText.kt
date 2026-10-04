package org.fossify.calendar.helpers

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

    enum class Unit { DAYS, HOURS, MINUTES }

    /** Largest whole unit for display: 2880 -> 2 days, 90 -> 90 minutes, 120 -> 2 hours. */
    fun durationParts(minutes: Long): Pair<Long, Unit> = when {
        minutes > 0 && minutes % MINUTES_PER_DAY == 0L -> Pair(minutes / MINUTES_PER_DAY, Unit.DAYS)
        minutes > 0 && minutes % MINUTES_PER_HOUR == 0L -> Pair(minutes / MINUTES_PER_HOUR, Unit.HOURS)
        else -> Pair(minutes, Unit.MINUTES)
    }
}
