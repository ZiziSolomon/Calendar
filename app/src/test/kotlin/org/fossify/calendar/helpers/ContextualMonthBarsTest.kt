package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualMonthBarsTest {
    private val day = ContextualMonthBars.MINUTES_PER_DAY

    private fun slice(dayIndex: Int, start: Int, end: Int, eventId: Long = 1L, occurrenceTS: Long = 100L, color: Int = 1, title: String = "On call") =
        ContextualStripe(dayIndex, start, end, color, title, eventId, occurrenceTS)

    @Test
    fun timedContextSitsAtItsTimeOfDay() {
        // Wednesday (column 2) 09:00 to 17:00
        val bar = ContextualMonthBars.layout(listOf(slice(2, 540, 1020))).single()
        assertEquals(0, bar.row)
        assertEquals(2 * day + 540, bar.start)
        assertEquals(2 * day + 1020, bar.end)
    }

    @Test
    fun multiDayContextIsOneBarThroughTheRow() {
        // Friday 18:00 to Sunday 18:00, as the builder slices it
        val bars = ContextualMonthBars.layout(listOf(slice(4, 1080, day), slice(5, 0, day), slice(6, 0, 1080)))
        assertEquals(1, bars.size)
        assertEquals(4 * day + 1080, bars.single().start)
        assertEquals(6 * day + 1080, bars.single().end)
    }

    @Test
    fun contextCrossingIntoTheNextWeekContinuesOnTheNextRow() {
        val bars = ContextualMonthBars.layout(listOf(slice(6, 0, day), slice(7, 0, 600)))
        assertEquals(listOf(0 to (6 * day until 7 * day), 1 to (0 until 600)), bars.map { it.row to (it.start until it.end) })
    }

    @Test
    fun separateOccurrencesOnNeighbouringDaysStaySeparate() {
        // a nightly context ending at 24:00 and the next night's starting at 00:00 would touch,
        // but they're two occurrences and keep two bars (and two taps)
        val bars = ContextualMonthBars.layout(listOf(slice(0, 1320, day, occurrenceTS = 1), slice(1, 0, 360, occurrenceTS = 2)))
        assertEquals(2, bars.size)
    }

    @Test
    fun concurrentContextsGetStackedLanes() {
        val bars = ContextualMonthBars.layout(listOf(slice(0, 0, day, eventId = 1), slice(0, 360, 840, eventId = 2, title = "Early shift")))
        assertEquals(listOf(0, 1), bars.map { it.lane })
        assertEquals(listOf("On call", "Early shift"), bars.map { it.title })
    }

    @Test
    fun aWeekLongContextKeepsOneLaneAllWeek() {
        // On call Mon-Sun plus a short context each evening: the long one never jumps lanes
        val slices = (0 until 7).map { slice(it, 0, day, eventId = 1) } +
            (0 until 7).map { slice(it, 1080, 1200, eventId = 2, occurrenceTS = it.toLong(), title = "Gym") }
        val bars = ContextualMonthBars.layout(slices)
        val onCall = bars.filter { it.eventId == 1L }
        assertEquals(1, onCall.size)
        assertEquals(0, onCall.single().lane)
        assertEquals(setOf(1), bars.filter { it.eventId == 2L }.map { it.lane }.toSet())
    }

    @Test
    fun rowsDoNotShareLanes() {
        val bars = ContextualMonthBars.layout(listOf(slice(0, 0, day, eventId = 1), slice(7, 0, day, eventId = 2)))
        assertEquals(listOf(0, 0), bars.map { it.lane })
    }

    @Test
    fun xRangeMapsMinutesAcrossTheCells() {
        val bar = ContextualMonthBars.layout(listOf(slice(1, 720, day))).single()
        // 100px cells after a 40px week-number column: Tuesday noon to Tuesday midnight
        assertEquals(Pair(40f + 150f, 40f + 200f), bar.xRange(dayWidth = 100f, offset = 40f))
    }

    @Test
    fun barEndingBeforeNowHasEnded() {
        // Monday 09:00-17:00; now is Monday 18:00 in the same row
        val bar = ContextualMonthBars.layout(listOf(slice(1, 540, 1020))).single()
        assertEquals(true, ContextualMonthBars.hasEnded(bar, nowDayIndex = 1, nowMinute = 1080))
        assertEquals(false, ContextualMonthBars.hasEnded(bar, nowDayIndex = 1, nowMinute = 600))
    }

    @Test
    fun barStillRunningHasNotEnded() {
        // On call Monday to Friday; now is Wednesday
        val bar = ContextualMonthBars.layout((1..5).map { slice(it, 0, day) }).single()
        assertEquals(false, ContextualMonthBars.hasEnded(bar, nowDayIndex = 3, nowMinute = 0))
    }

    @Test
    fun earlierRowsHaveEndedLaterOnesHaveNot() {
        val bars = ContextualMonthBars.layout(listOf(slice(0, 0, day, eventId = 1), slice(14, 0, day, eventId = 2)))
        assertEquals(listOf(true, false), bars.map { ContextualMonthBars.hasEnded(it, nowDayIndex = 9, nowMinute = 0) })
    }

    @Test
    fun offGridNowDimsAllOrNothing() {
        val bar = ContextualMonthBars.layout(listOf(slice(41, 0, day))).single()
        assertEquals(true, ContextualMonthBars.hasEnded(bar, nowDayIndex = 42, nowMinute = 0))
        assertEquals(false, ContextualMonthBars.hasEnded(bar, nowDayIndex = -1, nowMinute = 0))
    }

    @Test
    fun noStripesNoBars() {
        assertEquals(emptyList<ContextualMonthBars.Bar>(), ContextualMonthBars.layout(emptyList()))
    }
}
