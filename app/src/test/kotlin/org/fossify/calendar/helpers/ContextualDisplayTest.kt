package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualDisplayTest {
    private fun rule(matchType: Int, calendarId: Long? = 1L, pattern: String = "", keyName: String? = null, keyColor: Int? = null) =
        ContextualRule(id = null, calendarId = calendarId, matchType = matchType, pattern = pattern, keyName = keyName, keyColor = keyColor)

    @Test
    fun noRulesNoOverrides() {
        assertEquals(ContextualDisplay.NONE, ContextualDisplay.resolve(emptyList()))
    }

    @Test
    fun aSingleRuleGivesItsNameAndColour() {
        assertEquals(ContextualDisplay.Result("Work", 5), ContextualDisplay.resolve(listOf(rule(MATCH_ALL, keyName = "Work", keyColor = 5))))
    }

    @Test
    fun aTitleRuleCarvesItsEventsOutOfACalendarRule() {
        // everything in Work is grey "Work", except on-call events, which are red "On call"
        val calendar = rule(MATCH_ALL, keyName = "Work", keyColor = 1)
        val title = rule(MATCH_TITLE_CONTAINS, keyName = "On call", keyColor = 2)
        assertEquals(ContextualDisplay.Result("On call", 2), ContextualDisplay.resolve(listOf(calendar, title)))
    }

    @Test
    fun nameAndColourResolveIndependently() {
        // the title rule only renames; the calendar rule's colour still applies
        val calendar = rule(MATCH_ALL, keyColor = 1)
        val title = rule(MATCH_TITLE_CONTAINS, keyName = "On call")
        assertEquals(ContextualDisplay.Result("On call", 1), ContextualDisplay.resolve(listOf(calendar, title)))
    }

    @Test
    fun aDirectMarkBeatsEveryRule() {
        val title = rule(MATCH_TITLE_REGEX, keyName = "Shifts")
        val mark = rule(MATCH_EVENT_ID, calendarId = null, keyName = "Big one")
        assertEquals("Big one", ContextualDisplay.resolve(listOf(title, mark)).keyName)
    }

    @Test
    fun aCalendarScopedRuleBeatsTheSameRuleOnEveryCalendar() {
        val everywhere = rule(MATCH_TITLE_CONTAINS, calendarId = null, keyName = "Shift")
        val scoped = rule(MATCH_TITLE_CONTAINS, calendarId = 3L, keyName = "Work shift")
        assertEquals("Work shift", ContextualDisplay.resolve(listOf(everywhere, scoped)).keyName)
    }

    @Test
    fun titleRulesBeatDurationRules() {
        val duration = rule(MATCH_DURATION_OVER, keyName = "Long")
        val title = rule(MATCH_TITLE_CONTAINS, keyName = "Trip")
        assertEquals("Trip", ContextualDisplay.resolve(listOf(duration, title)).keyName)
    }

    @Test
    fun equalRulesGoToTheEarlierOne() {
        val first = rule(MATCH_TITLE_CONTAINS, keyName = "First")
        val second = rule(MATCH_TITLE_CONTAINS, keyName = "Second")
        assertEquals("First", ContextualDisplay.resolve(listOf(first, second)).keyName)
    }

    @Test
    fun aBlankNameIsNoName() {
        val specific = rule(MATCH_TITLE_CONTAINS, keyName = "   ")
        val calendar = rule(MATCH_ALL, keyName = "Work")
        assertEquals("Work", ContextualDisplay.resolve(listOf(specific, calendar)).keyName)
    }

    @Test
    fun namesAreTrimmed() {
        assertEquals("On call", ContextualDisplay.resolve(listOf(rule(MATCH_ALL, keyName = " On call "))).keyName)
    }

    @Test
    fun evaluatorResolvesOnlyTheRulesThatMatch() {
        // a non-matching title rule's name must not leak onto the event
        val evaluator = ContextualRuleEvaluator(
            listOf(
                rule(MATCH_ALL, keyName = "Work", keyColor = 1),
                rule(MATCH_TITLE_CONTAINS, pattern = "on call", keyName = "On call", keyColor = 2),
            )
        )
        val meeting = Event(id = 1L, title = "Standup", calendarId = 1L)
        val onCall = Event(id = 2L, title = "On call (Sam)", calendarId = 1L)
        assertEquals(ContextualDisplay.Result("Work", 1), evaluator.display(meeting))
        assertEquals(ContextualDisplay.Result("On call", 2), evaluator.display(onCall))
    }

    @Test
    fun evaluatorWithoutOverridesSkipsTheLookup() {
        val evaluator = ContextualRuleEvaluator(listOf(rule(MATCH_ALL)))
        assertEquals(ContextualDisplay.NONE, evaluator.display(Event(id = 1L, title = "x", calendarId = 1L)))
    }
}
