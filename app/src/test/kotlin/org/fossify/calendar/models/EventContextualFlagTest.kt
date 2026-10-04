package org.fossify.calendar.models

import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventContextualFlagTest {
    @Test
    fun togglingLeavesOtherFlagsAlone() {
        val event = Event(id = 1L, flags = FLAG_ALL_DAY)
        event.isContextual = true
        assertTrue(event.isContextual)
        assertTrue(event.getIsAllDay())

        event.isContextual = false
        assertFalse(event.isContextual)
        assertTrue(event.getIsAllDay())
    }

    @Test
    fun changesTheHashWeekViewUsesToSkipRedraws() {
        // WeekFragment.updateWeeklyCalendar() skips rendering when the list hash is unchanged,
        // so a rule change must change the hash or the week view would never show it
        val plain = arrayListOf(Event(id = 1L, title = "Shift"))
        val marked = arrayListOf(Event(id = 1L, title = "Shift").apply { isContextual = true })
        assertNotEquals(plain.hashCode(), marked.hashCode())
    }
}
