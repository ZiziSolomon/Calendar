package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualStripeDescriptionTest {
    private val templates = ContextualStripeDescription.Templates(
        allDay = "%s, all day",
        from = "%1\$s, from %2\$s",
        until = "%1\$s, until %2\$s",
        range = "%1\$s, %2\$s to %3\$s",
        untitled = "Untitled context",
    )

    private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    private fun stripe(day: Int, start: Int, end: Int, title: String) = ContextualStripe(day, start, end, 0, title)

    private fun describe(vararg stripes: ContextualStripe, day: Int = 0) =
        ContextualStripeDescription.describe(stripes.toList(), day, templates, ::hhmm)

    @Test
    fun dayWithoutContextsHasNoDescription() {
        assertEquals(emptyList<String>(), describe(stripe(1, 0, 1440, "On call"), day = 0))
    }

    @Test
    fun fullDaySliceIsAllDay() {
        assertEquals(listOf("Kids weekend, all day"), describe(stripe(0, 0, 1440, "Kids weekend")))
    }

    @Test
    fun sliceRunningPastMidnightSaysFrom() {
        // first day of a multi-day on-call week starting at 09:00
        assertEquals(listOf("On call, from 09:00"), describe(stripe(0, 540, 1440, "On call")))
    }

    @Test
    fun sliceStartingAtMidnightSaysUntil() {
        assertEquals(listOf("On call, until 17:30"), describe(stripe(0, 0, 1050, "On call")))
    }

    @Test
    fun timedSliceGivesBothEnds() {
        assertEquals(listOf("Early shift, 06:00 to 14:00"), describe(stripe(0, 360, 840, "Early shift")))
    }

    @Test
    fun contextsAreSpokenEarliestFirstOuterBeforeNested() {
        // builder order is longest first; speech should follow the clock instead
        val result = describe(
            stripe(0, 960, 1020, "Dentist"),
            stripe(0, 360, 840, "Early shift"),
            stripe(0, 360, 1440, "On call"),
        )
        assertEquals(listOf("On call, from 06:00", "Early shift, 06:00 to 14:00", "Dentist, 16:00 to 17:00"), result)
    }

    @Test
    fun blankTitleFallsBackToUntitled() {
        assertEquals(listOf("Untitled context, all day"), describe(stripe(0, 0, 1440, "  ")))
    }

    @Test
    fun identicalPhrasesAreSpokenOnce() {
        // two events with the same title and time would otherwise be read twice in a row
        assertEquals(listOf("Gym, 18:00 to 19:00"), describe(stripe(0, 1080, 1140, "Gym"), stripe(0, 1080, 1140, "Gym")))
    }
}
