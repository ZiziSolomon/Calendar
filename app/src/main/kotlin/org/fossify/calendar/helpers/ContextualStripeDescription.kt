package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe

/**
 * Spoken description of one week-view day's contexts, for TalkBack. Stripes are drawn on a
 * canvas, so without this a screen reader user never learns they exist.
 * Pure (templates and time formatting passed in) so it can be unit tested on the JVM.
 */
object ContextualStripeDescription {
    private const val MINUTES_PER_DAY = 1440

    /** Android format strings, e.g. "%1$s, from %2$s". */
    class Templates(
        val allDay: String,
        val from: String,
        val until: String,
        val range: String,
        val untitled: String,
    )

    /** One phrase per context on [dayIndex], earliest first, or empty if the day has none. */
    fun describe(
        stripes: List<ContextualStripe>,
        dayIndex: Int,
        templates: Templates,
        formatMinute: (Int) -> String,
    ): List<String> {
        return stripes
            .filter { it.dayIndex == dayIndex }
            // outer context first when two start together, matching the stacked labels
            .sortedWith(compareBy<ContextualStripe> { it.startMinute }.thenByDescending { it.endMinute })
            .map { stripe ->
                val title = stripe.title.ifBlank { templates.untitled }
                val startsAtMidnight = stripe.startMinute == 0
                val runsToMidnight = stripe.endMinute >= MINUTES_PER_DAY
                when {
                    startsAtMidnight && runsToMidnight -> templates.allDay.format(title)
                    // "until 24:00" / "12:00 AM" reads badly, and a slice that runs off the day
                    // is usually a multi-day context carrying on
                    runsToMidnight -> templates.from.format(title, formatMinute(stripe.startMinute))
                    startsAtMidnight -> templates.until.format(title, formatMinute(stripe.endMinute))
                    else -> templates.range.format(title, formatMinute(stripe.startMinute), formatMinute(stripe.endMinute))
                }
            }
            .distinct()
    }
}
