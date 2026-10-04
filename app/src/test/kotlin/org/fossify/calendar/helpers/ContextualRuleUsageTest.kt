package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualRuleUsageTest {
    private fun event(id: Long, title: String, calendarId: Long = 1L, parentId: Long = 0L, start: Long = id * 3600) =
        Event(id = id, title = title, calendarId = calendarId, parentId = parentId, startTS = start, endTS = start + 1800)

    private fun contains(text: String) = ContextualRule(id = null, matchType = MATCH_TITLE_CONTAINS, pattern = text)

    @Test
    fun aTypoRuleMatchesNothing() {
        val events = listOf(event(1, "On call"), event(2, "Dentist"))
        assertEquals(listOf(1, 0), ContextualRuleUsage.count(listOf(contains("on call"), contains("on cal l")), events))
    }

    @Test
    fun aRuleForAGoneCalendarMatchesNothing() {
        val rule = ContextualRule(id = null, matchType = MATCH_ALL, calendarId = 9L)
        assertEquals(listOf(0), ContextualRuleUsage.count(listOf(rule), listOf(event(1, "Shift", calendarId = 1L))))
    }

    @Test
    fun aRepeatingEventCountsOnceWithItsEditedOccurrences() {
        // the same series expanded into three occurrences (same id), plus an edited occurrence
        val events = listOf(
            event(5, "Gym", start = 0), event(5, "Gym", start = 7 * 86400), event(5, "Gym", start = 14 * 86400),
            event(6, "Gym late", parentId = 5)
        )
        assertEquals(listOf(1), ContextualRuleUsage.count(listOf(contains("gym")), events))
    }

    @Test
    fun countsEachRuleOnItsOwnEvenWhenSwitchedOff() {
        val off = contains("shift").apply { enabled = false }
        val events = listOf(event(1, "Early shift"), event(2, "Late shift"), event(3, "Lunch"))
        assertEquals(listOf(2, 3), ContextualRuleUsage.count(listOf(off, ContextualRule(id = null, matchType = MATCH_ALL)), events))
    }
}
