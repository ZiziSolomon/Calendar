package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRuleEvaluatorTest {
    // a zone with DST, so the clocks-change day (25 Oct 2026, 25h long) is exercised
    private val zone = DateTimeZone.forID("Europe/London")

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        DateTime(year, month, day, hour, minute, zone).millis / 1000L

    private fun timed(start: Long, end: Long, title: String = "Event", calendarId: Long = 1L) =
        Event(id = 100L, startTS = start, endTS = end, title = title, calendarId = calendarId)

    // mirrors how the app stores all-day events: local midnight on the first day to noon on the last
    private fun allDay(year: Int, month: Int, firstDay: Int, lastDay: Int) =
        Event(id = 100L, startTS = ts(year, month, firstDay), endTS = ts(year, month, lastDay, 12), flags = FLAG_ALL_DAY)

    private fun evaluator(vararg rules: ContextualRule) = ContextualRuleEvaluator(rules.toList(), zone)

    private fun durationOver(minutes: String) = ContextualRule(id = 1L, matchType = MATCH_DURATION_OVER, pattern = minutes)

    private val oneDayMinutes = "1440"

    // --- empty and disabled ---

    @Test
    fun noRulesMeansNothingIsContextual() {
        val evaluator = evaluator()
        assertTrue(evaluator.isEmpty)
        assertFalse(evaluator.isContextual(timed(ts(2026, 10, 5, 9), ts(2026, 10, 5, 10))))
    }

    @Test
    fun disabledRulesAreIgnored() {
        val evaluator = evaluator(ContextualRule(id = 1L, matchType = MATCH_ALL, enabled = false))
        assertTrue(evaluator.isEmpty)
        assertFalse(evaluator.isContextual(timed(ts(2026, 10, 5, 9), ts(2026, 10, 5, 10))))
    }

    // --- duration: the all-day boundary (§1.2) ---

    @Test
    fun singleAllDayEventIsNotOverOneDay() {
        assertFalse(evaluator(durationOver(oneDayMinutes)).isContextual(allDay(2026, 10, 5, 5)))
    }

    @Test
    fun singleAllDayEventCountsAsAFullDayNotTwelveHours() {
        // stored as 12h raw; a naive endTS - startTS would wrongly pass/fail these
        assertTrue(evaluator(durationOver("1439")).isContextual(allDay(2026, 10, 5, 5)))
        assertTrue(evaluator(durationOver("780")).isContextual(allDay(2026, 10, 5, 5)))
    }

    @Test
    fun singleAllDayEventOnTheDstChangeDayIsStillOneDay() {
        // 25 Oct 2026 is 25h long in London; must not be judged longer than a day
        assertFalse(evaluator(durationOver(oneDayMinutes)).isContextual(allDay(2026, 10, 25, 25)))
    }

    @Test
    fun multiDayAllDayEventIsOverOneDay() {
        // "kids at their dad's, Fri–Sun"
        assertTrue(evaluator(durationOver(oneDayMinutes)).isContextual(allDay(2026, 10, 2, 4)))
        assertTrue(evaluator(durationOver("2880")).isContextual(allDay(2026, 10, 2, 4)))
        assertFalse(evaluator(durationOver("4320")).isContextual(allDay(2026, 10, 2, 4)))
    }

    @Test
    fun twoDayAllDayEventIsOverOneDay() {
        assertTrue(evaluator(durationOver(oneDayMinutes)).isContextual(allDay(2026, 10, 5, 6)))
    }

    // --- duration: timed events ---

    @Test
    fun timedEventOfExactlyTheThresholdIsNotOver() {
        assertFalse(evaluator(durationOver(oneDayMinutes)).isContextual(timed(ts(2026, 10, 5, 9), ts(2026, 10, 6, 9))))
    }

    @Test
    fun multiDayTimedEventIsOver() {
        assertTrue(evaluator(durationOver(oneDayMinutes)).isContextual(timed(ts(2026, 10, 5, 9), ts(2026, 10, 6, 10))))
    }

    @Test
    fun shortEventCrossingMidnightIsMeasuredByTimeNotDays() {
        // spans two day codes but is only 2h long
        assertFalse(evaluator(durationOver("120")).isContextual(timed(ts(2026, 10, 5, 23), ts(2026, 10, 6, 1))))
        assertTrue(evaluator(durationOver("119")).isContextual(timed(ts(2026, 10, 5, 23), ts(2026, 10, 6, 1))))
    }

    @Test
    fun unparseableOrNegativeDurationNeverMatches() {
        val longEvent = timed(ts(2026, 10, 1), ts(2026, 10, 20))
        assertFalse(evaluator(durationOver("")).isContextual(longEvent))
        assertFalse(evaluator(durationOver("a day")).isContextual(longEvent))
        assertFalse(evaluator(durationOver("-5")).isContextual(longEvent))
    }

    // --- title matching ---

    @Test
    fun titleContainsIsCaseInsensitive() {
        val evaluator = evaluator(ContextualRule(id = 1L, matchType = MATCH_TITLE_CONTAINS, pattern = "on call"))
        assertTrue(evaluator.isContextual(timed(0, 60, title = "ON CALL - week 3")))
        assertFalse(evaluator.isContextual(timed(0, 60, title = "Dentist")))
    }

    @Test
    fun blankPatternsMatchNothing() {
        val event = timed(0, 60, title = "Anything")
        assertFalse(evaluator(ContextualRule(id = 1L, matchType = MATCH_TITLE_CONTAINS, pattern = "  ")).isContextual(event))
        assertFalse(evaluator(ContextualRule(id = 1L, matchType = MATCH_TITLE_REGEX, pattern = "")).isContextual(event))
    }

    @Test
    fun regexSearchesAnywhereInTheTitleIgnoringCase() {
        val evaluator = evaluator(ContextualRule(id = 1L, matchType = MATCH_TITLE_REGEX, pattern = "^ctx:"))
        assertTrue(evaluator.isContextual(timed(0, 60, title = "Ctx: school term")))
        assertFalse(evaluator.isContextual(timed(0, 60, title = "Not Ctx: at start")))
    }

    @Test
    fun malformedRegexDisablesOnlyItsOwnRule() {
        val evaluator = evaluator(
            ContextualRule(id = 1L, matchType = MATCH_TITLE_REGEX, pattern = "(["),
            ContextualRule(id = 2L, matchType = MATCH_TITLE_CONTAINS, pattern = "shift")
        )
        assertFalse(evaluator.isContextual(timed(0, 60, title = "([")))
        assertTrue(evaluator.isContextual(timed(0, 60, title = "Night shift")))
    }

    @Test
    fun regexErrorExplainsBadPatternsOnly() {
        assertNotNull(ContextualRuleEvaluator.regexError("(["))
        assertNull(ContextualRuleEvaluator.regexError("^Ctx:"))
    }

    // --- scoping and OR semantics ---

    @Test
    fun calendarScopedRuleOnlyMatchesThatCalendar() {
        val evaluator = evaluator(ContextualRule(id = 1L, calendarId = 2L, matchType = MATCH_ALL))
        assertTrue(evaluator.isContextual(timed(0, 60, calendarId = 2L)))
        assertFalse(evaluator.isContextual(timed(0, 60, calendarId = 3L)))
    }

    @Test
    fun globalRuleMatchesAnyCalendar() {
        val evaluator = evaluator(ContextualRule(id = 1L, calendarId = null, matchType = MATCH_TITLE_CONTAINS, pattern = "Kids"))
        assertTrue(evaluator.isContextual(timed(0, 60, title = "Kids: Dad's", calendarId = 2L)))
        assertTrue(evaluator.isContextual(timed(0, 60, title = "Kids: Dad's", calendarId = 7L)))
    }

    @Test
    fun anyMatchingRuleIsEnough() {
        val evaluator = evaluator(
            ContextualRule(id = 1L, matchType = MATCH_TITLE_CONTAINS, pattern = "shift"),
            durationOver(oneDayMinutes)
        )
        assertTrue(evaluator.isContextual(timed(ts(2026, 10, 5, 9), ts(2026, 10, 5, 17), title = "Early shift")))
        assertTrue(evaluator.isContextual(allDay(2026, 10, 2, 4)))
        assertFalse(evaluator.isContextual(timed(ts(2026, 10, 5, 14), ts(2026, 10, 5, 15), title = "Dentist")))
    }

    @Test
    fun unknownMatchTypeNeverMatches() {
        assertFalse(evaluator(ContextualRule(id = 1L, matchType = 99)).isContextual(timed(0, 60)))
    }

    // --- per-event marking ---

    @Test
    fun eventIdRuleMatchesTheEventAndItsEditedOccurrences() {
        val evaluator = evaluator(ContextualRule(id = 1L, matchType = MATCH_EVENT_ID, eventId = 100L))
        assertTrue(evaluator.isContextual(timed(0, 60)))
        assertTrue(evaluator.isContextual(timed(0, 60).apply { id = 101L; parentId = 100L }))
        assertFalse(evaluator.isContextual(timed(0, 60).apply { id = 102L }))
    }

    @Test
    fun importIdRepairsAMarkAfterIdsAreReassigned() {
        val evaluator = evaluator(ContextualRule(id = 1L, matchType = MATCH_EVENT_ID, eventId = 5L, importId = "caldav-3-77"))
        assertTrue(evaluator.isContextual(timed(0, 60).apply { id = 200L; importId = "caldav-3-77" }))
    }

    @Test
    fun emptyImportIdNeverMatchesLocalEvents() {
        // local-only events all have importId = "", so an empty key would mark every one of them
        val evaluator = evaluator(
            ContextualRule(id = 1L, matchType = MATCH_EVENT_ID, eventId = 5L, importId = ""),
            ContextualRule(id = 2L, matchType = MATCH_EVENT_ID, eventId = 6L, importId = null)
        )
        assertFalse(evaluator.isContextual(timed(0, 60).apply { id = 200L; importId = "" }))
    }

    @Test
    fun rootEventsWithNoParentAreNotMatchedByAZeroId() {
        // parentId == 0 means "no parent"; it must not be compared with a rule's id
        val evaluator = evaluator(ContextualRule(id = 1L, matchType = MATCH_EVENT_ID, eventId = 0L))
        assertFalse(evaluator.isContextual(timed(0, 60).apply { id = 200L }))
    }

    @Test
    fun rulesWithIdenticalContentDoNotInterfere() {
        // unsaved rules (id = null) from the editor preview are compared by value
        val evaluator = evaluator(
            ContextualRule(id = null, matchType = MATCH_TITLE_REGEX, pattern = "gym"),
            ContextualRule(id = null, matchType = MATCH_TITLE_REGEX, pattern = "gym")
        )
        assertTrue(evaluator.isContextual(timed(0, 60, title = "Gym")))
    }

    @Test
    fun durationIsReportedInWholeDaysForAllDayEvents() {
        val evaluator = evaluator()
        assertEquals(1440L, evaluator.durationMinutes(allDay(2026, 10, 25, 25)))
        assertEquals(4320L, evaluator.durationMinutes(allDay(2026, 10, 2, 4)))
    }
}
