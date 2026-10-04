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

    private fun build(vararg events: Event, daysCount: Int = 7, fallbackColor: Int = 0) =
        ContextualStripeBuilder.build(events.toList(), monday, daysCount, zone, fallbackColor)

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
    fun noEventsNoStripes() {
        assertEquals(emptyList<ContextualStripe>(), build())
    }
}
