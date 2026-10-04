package org.fossify.calendar.models

import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListEventContextualTest {
    @Test
    fun carriesTheContextualFlagFromTheEvent() {
        val event = Event(id = 7L, title = "On call", flags = FLAG_ALL_DAY).apply { isContextual = true }
        val listEvent = ListEvent.from(event)
        assertTrue(listEvent.isContextual)
        assertTrue(listEvent.isAllDay)
        assertEquals("On call", listEvent.title)
    }

    @Test
    fun plainEventsStayUntagged() {
        val listEvent = ListEvent.from(Event(id = 8L, title = "Dentist"))
        assertFalse(listEvent.isContextual)
        assertEquals("09:00 - 10:00", listEvent.tagTime("09:00 - 10:00", "Context"))
    }

    @Test
    fun contextualRowsSayContextAfterTheTime() {
        val listEvent = ListEvent.from(Event(id = 9L).apply { isContextual = true })
        assertEquals("All day · Context", listEvent.tagTime("All day", "Context"))
    }

    @Test
    fun flagChangesTheHashTheListUsesToSkipRedraws() {
        // EventListAdapter.updateListItems() skips the redraw when the list hash is unchanged,
        // so marking an event must change it or the tag would only appear after a restart
        val plain = ListEvent.from(Event(id = 1L, title = "Shift"))
        val marked = ListEvent.from(Event(id = 1L, title = "Shift").apply { isContextual = true })
        assertNotEquals(plain.hashCode(), marked.hashCode())
    }
}
