package org.fossify.calendar.helpers

import org.fossify.calendar.models.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContextualRenderKeyTest {
    private fun events() = listOf(Event(id = 1L, title = "On call").apply { isContextual = true }, Event(id = 2L, title = "Dentist"))

    @Test
    fun sameEventsAndSettingSkipTheRedraw() {
        assertEquals(ContextualRenderKey.of(events(), true), ContextualRenderKey.of(events(), true))
    }

    @Test
    fun togglingContextsForcesARedraw() {
        assertNotEquals(ContextualRenderKey.of(events(), true), ContextualRenderKey.of(events(), false))
    }

    @Test
    fun anEmptyDayStillRedrawsWhenToggled() {
        // an empty list hashes to 1; the key must still differ, and differ from a fresh fragment's 0
        assertNotEquals(ContextualRenderKey.of(emptyList(), true), ContextualRenderKey.of(emptyList(), false))
        assertNotEquals(0, ContextualRenderKey.of(emptyList(), true))
        assertNotEquals(0, ContextualRenderKey.of(emptyList(), false))
    }
}
