package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.ContextualRuleText.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualRuleTextTest {
    @Test
    fun hoursBecomeMinutes() {
        assertEquals("1440", ContextualRuleText.hoursInputToPattern("24"))
        assertEquals("90", ContextualRuleText.hoursInputToPattern(" 1.5 "))
        assertEquals("90", ContextualRuleText.hoursInputToPattern("1,5"))
    }

    @Test
    fun nonsenseHoursAreRejected() {
        assertNull(ContextualRuleText.hoursInputToPattern(""))
        assertNull(ContextualRuleText.hoursInputToPattern("a day"))
        assertNull(ContextualRuleText.hoursInputToPattern("0"))
        assertNull(ContextualRuleText.hoursInputToPattern("-3"))
        assertNull(ContextualRuleText.hoursInputToPattern("NaN"))
        assertNull(ContextualRuleText.hoursInputToPattern("Infinity"))
    }

    @Test
    fun minutesRoundTripToHours() {
        assertEquals("24", ContextualRuleText.patternToHoursInput("1440"))
        assertEquals("1.5", ContextualRuleText.patternToHoursInput("90"))
        assertEquals("", ContextualRuleText.patternToHoursInput("garbage"))
    }

    @Test
    fun durationUsesTheLargestWholeUnit() {
        assertEquals(Pair(2L, Unit.DAYS), ContextualRuleText.durationParts(2880))
        assertEquals(Pair(36L, Unit.HOURS), ContextualRuleText.durationParts(2160))
        assertEquals(Pair(90L, Unit.MINUTES), ContextualRuleText.durationParts(90))
        assertEquals(Pair(0L, Unit.MINUTES), ContextualRuleText.durationParts(0))
    }

    @Test
    fun ruleFromTitleIsTitleContainsLimitedToTheCalendar() {
        val rule = ContextualRuleText.ruleFromTitle("  On call ", 3L)!!
        assertEquals(MATCH_TITLE_CONTAINS, rule.matchType)
        assertEquals("On call", rule.pattern)
        assertEquals(3L, rule.calendarId)
        assertNull(rule.id)
        assertEquals(true, rule.enabled)
    }

    @Test
    fun ruleFromTitleMatchesTheEventItCameFrom() {
        val event = org.fossify.calendar.models.Event(id = 1L, title = "Kids weekend (Dad's)", calendarId = 2L)
        val rule = ContextualRuleText.ruleFromTitle(event.title, event.calendarId)!!
        // regex metacharacters are harmless: "contains" is a plain substring match
        assertEquals(true, ContextualRuleEvaluator(listOf(rule)).isContextual(event))
        assertEquals(false, ContextualRuleEvaluator(listOf(rule)).isContextual(event.copy(calendarId = 5L)))
    }

    @Test
    fun blankTitlesGiveNoRule() {
        assertNull(ContextualRuleText.ruleFromTitle("", 1L))
        assertNull(ContextualRuleText.ruleFromTitle("   ", 1L))
    }
}
