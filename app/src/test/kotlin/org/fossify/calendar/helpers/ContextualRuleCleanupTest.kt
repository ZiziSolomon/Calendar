package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRuleCleanupTest {
    private val markOn10 = ContextualRule(id = 1, matchType = MATCH_EVENT_ID, eventId = 10, importId = "Caldav-1-1")
    private val markOn11 = ContextualRule(id = 2, matchType = MATCH_EVENT_ID, eventId = 11)
    private val everyEventInWork = ContextualRule(id = 3, matchType = MATCH_ALL, calendarId = 4)
    private val gymInWork = ContextualRule(id = 4, matchType = MATCH_TITLE_CONTAINS, pattern = "gym", calendarId = 4)
    private val shiftAnywhere = ContextualRule(id = 5, matchType = MATCH_TITLE_CONTAINS, pattern = "shift")
    private val rules = listOf(markOn10, markOn11, everyEventInWork, gymInWork, shiftAnywhere)

    @Test
    fun deletingAnEventDropsOnlyItsMark() {
        assertEquals(listOf(markOn10), ContextualRuleCleanup.orphanedBy(rules, deletedEventIds = listOf(10L, 99L)))
    }

    @Test
    fun deletingACalendarDropsEveryRuleScopedToIt() {
        assertEquals(listOf(everyEventInWork, gymInWork), ContextualRuleCleanup.orphanedBy(rules, deletedCalendarIds = listOf(4L)))
    }

    @Test
    fun eventIdsNeverMatchCalendarScopesOrViceVersa() {
        // id 4 as an event must not take out the rules scoped to calendar 4, and 10 as a calendar no mark
        assertTrue(ContextualRuleCleanup.orphanedBy(rules, deletedEventIds = listOf(4L)).isEmpty())
        assertTrue(ContextualRuleCleanup.orphanedBy(rules, deletedCalendarIds = listOf(10L, 11L)).isEmpty())
    }

    @Test
    fun unscopedRulesSurviveAnyDeletion() {
        val orphans = ContextualRuleCleanup.orphanedBy(rules, listOf(10L, 11L), listOf(4L))
        assertEquals(rules - shiftAnywhere, orphans)
    }

    @Test
    fun nothingDeletedNothingOrphaned() {
        assertTrue(ContextualRuleCleanup.orphanedBy(rules).isEmpty())
    }
}
