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

    @Test
    fun matchesShowEachSeriesAtItsNextOccurrence() {
        val week = 7 * 86400L
        val events = listOf(
            event(5, "Gym", start = 0), event(5, "Gym", start = week), event(5, "Gym", start = 2 * week),
            event(6, "Gym late", parentId = 5, start = 3 * week)
        )
        val matches = ContextualRuleUsage.matches(contains("gym"), events, nowTS = week - 1)
        assertEquals(1, matches.size)
        assertEquals(week, matches[0].startTS)
    }

    @Test
    fun upcomingSoonestFirstThenPastMostRecentFirst() {
        val events = listOf(event(1, "Shift A", start = 100), event(2, "Shift B", start = 900), event(3, "Shift C", start = 500), event(4, "Shift D", start = 300))
        val matches = ContextualRuleUsage.matches(contains("shift"), events, nowTS = 400)
        assertEquals(listOf(3L, 2L, 4L, 1L), matches.map { it.id })
    }

    @Test
    fun aSeriesEntirelyInThePastShowsItsLatestOccurrence() {
        val events = listOf(event(7, "On call", start = 100), event(7, "On call", start = 200))
        assertEquals(200L, ContextualRuleUsage.matches(contains("on call"), events, nowTS = 1000).single().startTS)
    }

    @Test
    fun countAgreesWithMatches() {
        val events = listOf(event(1, "Shift"), event(1, "Shift", start = 99_999), event(2, "Late shift"), event(3, "Lunch"))
        val rule = contains("shift")
        assertEquals(ContextualRuleUsage.matches(rule, events, nowTS = 0).size, ContextualRuleUsage.count(listOf(rule), events).single())
    }
}
