package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualStripeBuilderTest {
    private val zone = DateTimeZone.forID("Europe/London")
    private val monday = LocalDate(2026, 10, 5)

    private fun ts(month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        DateTime(2026, month, day, hour, minute, zone).millis / 1000L

    private fun timed(start: Long, end: Long, color: Int = 0xABCDEF) =
        Event(id = 1L, startTS = start, endTS = end, color = color)

    private fun allDay(firstDay: Int, lastDay: Int) =
        Event(id = 1L, startTS = ts(10, firstDay), endTS = ts(10, lastDay, 12), flags = FLAG_ALL_DAY, color = 0xABCDEF)

    // geometry tests: the open-target fields are checked once, in stripeKnowsWhichOccurrenceToOpen
    private fun build(vararg events: Event, daysCount: Int = 7, fallbackColor: Int = 0) =
        ContextualStripeBuilder.build(events.toList(), monday, daysCount, zone, fallbackColor)
            .map { it.copy(eventId = 0L, occurrenceTS = 0L, isTask = false) }

    @Test
    fun stripeKnowsWhichOccurrenceToOpen() {
        val event = Event(id = 42L, startTS = ts(10, 6, 9), endTS = ts(10, 7, 17), color = 1)
        val stripes = ContextualStripeBuilder.build(listOf(event), monday, 7, zone)
        // every day-slice of a multi-day event opens the same occurrence
        assertEquals(listOf(42L, 42L), stripes.map { it.eventId })
        assertEquals(listOf(ts(10, 6, 9), ts(10, 6, 9)), stripes.map { it.occurrenceTS })
        assertEquals(listOf(false, false), stripes.map { it.isTask })
    }

    @Test
    fun timedEventBecomesOneStripeInItsColumn() {
        val stripes = build(timed(ts(10, 7, 9), ts(10, 7, 17, 30)))
        assertEquals(listOf(ContextualStripe(2, 9 * 60, 17 * 60 + 30, 0xABCDEF)), stripes)
    }

    @Test
    fun allDayEventFillsWholeColumns() {
        // Fri–Sun, stored midnight to noon on Sunday
        val stripes = build(allDay(9, 11))
        assertEquals(
            listOf(
                ContextualStripe(4, 0, 1440, 0xABCDEF),
                ContextualStripe(5, 0, 1440, 0xABCDEF),
                ContextualStripe(6, 0, 1440, 0xABCDEF)
            ),
            stripes
        )
    }

    @Test
    fun daysOutsideTheVisibleWeekAreDropped() {
        // Sat 3 Oct – Tue 6 Oct: only Mon and Tue are on screen
        val stripes = build(allDay(3, 6))
        assertEquals(listOf(0, 1), stripes.map { it.dayIndex })
    }

    @Test
    fun respectsAShorterVisibleDayCount() {
        val stripes = build(allDay(5, 11), daysCount = 3)
        assertEquals(listOf(0, 1, 2), stripes.map { it.dayIndex })
    }

    @Test
    fun eventWithNoOwnColourUsesTheFallback() {
        val stripes = build(timed(ts(10, 5, 9), ts(10, 5, 10), color = 0), fallbackColor = 0x123456)
        assertEquals(0x123456, stripes.single().color)
    }

    @Test
    fun everySliceCarriesTheEventTitleForItsLabel() {
        val event = Event(id = 1L, startTS = ts(10, 9, 18), endTS = ts(10, 11, 18), title = "Away")
        assertEquals(listOf("Away", "Away", "Away"), build(event).map { it.title })
    }

    @Test
    fun noEventsNoStripes() {
        assertEquals(emptyList<ContextualStripe>(), build())
    }

    // --- P3.12 edge cases ---

    @Test
    fun eventCrossingMidnightIsSplitAcrossColumns() {
        val stripes = build(timed(ts(10, 5, 22), ts(10, 6, 2)))
        assertEquals(
            setOf(ContextualStripe(0, 22 * 60, 1440, 0xABCDEF), ContextualStripe(1, 0, 2 * 60, 0xABCDEF)),
            stripes.toSet()
        )
    }

    @Test
    fun eventEndingExactlyAtMidnightLeavesNoSliverOnTheNextDay() {
        val stripes = build(timed(ts(10, 5, 20), ts(10, 6, 0)))
        assertEquals(listOf(ContextualStripe(0, 20 * 60, 1440, 0xABCDEF)), stripes)
    }

    @Test
    fun multiDayTimedEventFillsTheDaysInBetween() {
        // Fri 18:00 – Sun 18:00
        val stripes = build(timed(ts(10, 9, 18), ts(10, 11, 18)))
        assertEquals(
            setOf(
                ContextualStripe(4, 18 * 60, 1440, 0xABCDEF),
                ContextualStripe(5, 0, 1440, 0xABCDEF),
                ContextualStripe(6, 0, 18 * 60, 0xABCDEF)
            ),
            stripes.toSet()
        )
    }

    @Test
    fun monthsLongEventOnlyProducesTheVisibleWeek() {
        val stripes = build(Event(id = 1L, startTS = ts(9, 1), endTS = ts(12, 18, 12), flags = FLAG_ALL_DAY))
        assertEquals((0..6).toList(), stripes.map { it.dayIndex }.sorted())
    }

    @Test
    fun zeroLengthEventGetsAMinimumHeight() {
        val stripes = build(timed(ts(10, 5, 9), ts(10, 5, 9)))
        assertEquals(listOf(ContextualStripe(0, 9 * 60, 9 * 60 + ContextualStripeBuilder.MIN_STRIPE_MINUTES, 0xABCDEF)), stripes)
    }

    @Test
    fun minimumHeightNeverRunsPastTheEndOfTheDay() {
        val stripes = build(timed(ts(10, 5, 23, 55), ts(10, 5, 23, 55)))
        assertEquals(1440, stripes.single().endMinute)
    }

    @Test
    fun allDayEventOnTheDstChangeDayFillsOneColumn() {
        // Sun 25 Oct 2026 is 25h long in London
        val sunday = LocalDate(2026, 10, 19)
        val event = Event(id = 1L, startTS = ts(10, 25), endTS = ts(10, 25, 12), flags = FLAG_ALL_DAY, color = 1)
        val stripes = ContextualStripeBuilder.build(listOf(event), sunday, 7, zone).map { it.copy(eventId = 0L, occurrenceTS = 0L) }
        assertEquals(listOf(ContextualStripe(6, 0, 1440, 1)), stripes)
    }

    @Test
    fun shorterContextNestedInALongerOneIsDrawnLast() {
        val onCallWeek = Event(id = 1L, startTS = ts(10, 5), endTS = ts(10, 11, 12), flags = FLAG_ALL_DAY, color = 1)
        val kidsWeekend = Event(id = 2L, startTS = ts(10, 10), endTS = ts(10, 11, 12), flags = FLAG_ALL_DAY, color = 2)
        val evening = timed(ts(10, 10, 18), ts(10, 10, 22), color = 3)

        // input order deliberately puts the short ones first
        val saturday = build(evening, kidsWeekend, onCallWeek).filter { it.dayIndex == 5 }
        assertEquals(listOf(1, 2, 3), saturday.map { it.color })
    }
}
