package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualRulePreviewTest {
    private fun event(id: Long, title: String, start: Long = id * 3600) = Event(id = id, title = title, startTS = start, endTS = start + 1800)

    private fun contains(text: String) = ContextualRule(id = null, matchType = MATCH_TITLE_CONTAINS, pattern = text)

    @Test
    fun countsMatchesOutOfAllEvents() {
        val events = listOf(event(1, "Early shift"), event(2, "Dentist"), event(3, "Late shift"))
        val result = ContextualRulePreview.compute(contains("shift"), events)
        assertEquals(2, result.matchCount)
        assertEquals(3, result.total)
    }

    @Test
    fun samplesAreTheSoonestFewMatches() {
        val events = (10L downTo 1L).map { event(it, "Shift $it") }
        val result = ContextualRulePreview.compute(contains("shift"), events)
        assertEquals(10, result.matchCount)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.samples.map { it.id })
    }

    @Test
    fun previewsADisabledRuleAsIfItWereOn() {
        val rule = contains("shift").apply { enabled = false }
        assertEquals(1, ContextualRulePreview.compute(rule, listOf(event(1, "Shift"))).matchCount)
    }

    @Test
    fun noEventsMeansNothingToMatch() {
        val result = ContextualRulePreview.compute(contains("shift"), emptyList())
        assertEquals(0, result.matchCount)
        assertEquals(0, result.total)
    }

    @Test
    fun ordinaryRegexOverManyEventsIsWellWithinBudget() {
        val rule = ContextualRule(id = null, matchType = MATCH_TITLE_REGEX, pattern = "^(on call|shift)\\b")
        val events = (1L..500L).map { event(it, if (it % 2 == 0L) "On call week $it" else "Meeting $it") }
        val result = BoundedRunner(ContextualRulePreview.TIME_BUDGET_MILLIS).run { ContextualRulePreview.compute(rule, events) }
        assertEquals(250, result?.matchCount)
    }
}
