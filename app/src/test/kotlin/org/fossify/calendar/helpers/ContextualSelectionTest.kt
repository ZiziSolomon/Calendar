package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.ContextualSelection.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualSelectionTest {
    @Test
    fun allMarkedOffersUnmark() {
        assertEquals(Action.UNMARK, ContextualSelection.action(listOf(1L, 2L), setOf(1L, 2L, 3L)))
    }

    @Test
    fun aMixedSelectionOffersMark() {
        assertEquals(Action.MARK, ContextualSelection.action(listOf(1L, 4L), setOf(1L, 2L)))
    }

    @Test
    fun nothingSelectedOffersMark() {
        assertEquals(Action.MARK, ContextualSelection.action(emptyList(), setOf(1L)))
    }

    @Test
    fun anEditedOccurrenceCountsAsMarkedThroughItsSeries() {
        // 646 is an edited occurrence of series 640, which carries the mark
        val seriesOf = mapOf(646L to 640L)
        val marked = ContextualSelection.markedAmong(listOf(646L, 700L), setOf(640L)) { seriesOf[it] ?: it }
        assertEquals(setOf(646L), marked)
    }

    @Test
    fun noMarksMeansNoLookups() {
        val marked = ContextualSelection.markedAmong(listOf(1L, 2L), emptySet()) { throw AssertionError("looked up $it") }
        assertEquals(emptySet<Long>(), marked)
    }
}
