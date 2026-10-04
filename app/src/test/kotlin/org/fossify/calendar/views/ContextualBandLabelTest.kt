package org.fossify.calendar.views

import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualBandLabelTest {
    private val zone = DateTimeZone.forID("Europe/London")
    private fun ts(day: Int, hour: Int) = DateTime(2026, 10, day, hour, 0, zone).millis / 1000L
    private val hourOnly: (Long) -> String = { "%02d".format(DateTime(it * 1000L, zone).hourOfDay) }

    private fun label(event: Event) = ContextualBandView.label(event, zone, hourOnly)

    @Test
    fun allDayShowsJustTheTitle() {
        assertEquals("Kids at Dad's", label(Event(id = 1L, title = "Kids at Dad's", startTS = ts(9, 0), endTS = ts(11, 12), flags = FLAG_ALL_DAY)))
    }

    @Test
    fun sameDayTimedShowsTheTimeRange() {
        assertEquals("On call · 09 – 17", label(Event(id = 1L, title = "On call", startTS = ts(5, 9), endTS = ts(5, 17))))
    }

    @Test
    fun multiDayTimedShowsJustTheTitle() {
        // a range like "18 – 18" would be misleading on any single day it covers
        assertEquals("Away", label(Event(id = 1L, title = "Away", startTS = ts(9, 18), endTS = ts(11, 18))))
    }
}
