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
}
