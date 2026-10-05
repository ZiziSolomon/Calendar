package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualKeyTest {
    private fun stripe(day: Int, start: Int, title: String, color: Int = 1, eventId: Long = 1L, occurrenceTS: Long = 0L) =
        ContextualStripe(day, start, start + 60, color, title, eventId, occurrenceTS)

    @Test
    fun oneEntryPerContextAcrossItsDays() {
        val entries = ContextualKey.entries(listOf(stripe(0, 0, "On call"), stripe(1, 0, "On call"), stripe(2, 0, "On call")))
        assertEquals(listOf("On call"), entries.map { it.title })
    }

    @Test
    fun separateWeeklyEventsWithTheSameTitleShareAnEntry() {
        // "On call" created fresh each week: different events, one key entry
        val entries = ContextualKey.entries(listOf(stripe(0, 0, "On call", eventId = 1), stripe(5, 0, "On call", eventId = 2)))
        assertEquals(1, entries.size)
        assertEquals(1L, entries.single().eventId)
    }

    @Test
    fun titlesMatchIgnoringCaseAndSpaces() {
        val entries = ContextualKey.entries(listOf(stripe(0, 0, "On call"), stripe(1, 0, " on call ")))
        assertEquals(1, entries.size)
    }

    @Test
    fun sameTitleInAnotherColourIsItsOwnEntry() {
        // otherwise the key would show one swatch for stripes of two colours
        val entries = ContextualKey.entries(listOf(stripe(0, 0, "Shift", color = 1), stripe(1, 0, "Shift", color = 2)))
        assertEquals(listOf(1, 2), entries.map { it.color })
    }

    @Test
    fun orderedByFirstAppearance() {
        // the builder hands stripes over longest first; the key reads in calendar order instead
        val entries = ContextualKey.entries(listOf(stripe(3, 0, "Kids weekend"), stripe(1, 600, "Gym"), stripe(1, 360, "Early shift")))
        assertEquals(listOf("Early shift", "Gym", "Kids weekend"), entries.map { it.title })
    }

    @Test
    fun entryOpensTheFirstOccurrenceOnScreen() {
        val entries = ContextualKey.entries(listOf(stripe(4, 0, "On call", occurrenceTS = 400), stripe(2, 0, "On call", occurrenceTS = 200)))
        assertEquals(200L, entries.single().occurrenceTS)
    }

    @Test
    fun noStripesNoKey() {
        assertEquals(emptyList<ContextualKey.Entry>(), ContextualKey.entries(emptyList()))
    }
}
