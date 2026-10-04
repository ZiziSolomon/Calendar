package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsContextualMarkTest {
    private fun event(id: Long, parentId: Long = 0L) = Event(id = id, startTS = 0L, endTS = 60L, title = "x", parentId = parentId)

    @Test
    fun onlyEnabledDirectMarksAreExported() {
        val rules = listOf(
            ContextualRule(id = 1L, matchType = MATCH_EVENT_ID, eventId = 10L),
            ContextualRule(id = 2L, matchType = MATCH_EVENT_ID, eventId = 11L, enabled = false),
            ContextualRule(id = 3L, matchType = MATCH_TITLE_CONTAINS, pattern = "call"),
            ContextualRule(id = 4L, matchType = MATCH_ALL, calendarId = 12L),
            ContextualRule(id = 5L, matchType = MATCH_EVENT_ID, eventId = null, importId = "abc")
        )
        assertEquals(setOf(10L), IcsContextualMark.markedEventIds(rules))
    }

    @Test
    fun anEditedOccurrenceIsMarkedThroughItsSeries() {
        val marked = setOf(10L)
        assertTrue(IcsContextualMark.isMarked(event(10L), marked))
        assertTrue(IcsContextualMark.isMarked(event(20L, parentId = 10L), marked))
        assertFalse(IcsContextualMark.isMarked(event(21L, parentId = 11L), marked))
    }

    @Test
    fun aRootEventIsNeverMatchedThroughParentIdZero() {
        assertFalse(IcsContextualMark.isMarked(event(30L), setOf(0L)))
    }

    @Test
    fun theExportedLineParsesBackAsMarked() {
        assertTrue(IcsContextualMark.LINE.startsWith(FOSSIFY_CONTEXTUAL))
        assertTrue(IcsContextualMark.parse(IcsContextualMark.LINE.substring(FOSSIFY_CONTEXTUAL.length)))
    }

    @Test
    fun parsingIsLenientAboutCaseAndWhitespaceButNothingElse() {
        assertTrue(IcsContextualMark.parse(" true "))
        assertFalse(IcsContextualMark.parse("FALSE"))
        assertFalse(IcsContextualMark.parse("1"))
        assertFalse(IcsContextualMark.parse(""))
    }
}
