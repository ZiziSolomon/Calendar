package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualReminderPolicyTest {
    private val onCall = Event(id = 1L, startTS = 0L, endTS = 3600L, title = "On call", calendarId = 1L)
    private val dentist = Event(id = 2L, startTS = 0L, endTS = 3600L, title = "Dentist", calendarId = 1L)
    private val rules = ContextualRuleEvaluator(listOf(ContextualRule(id = 1L, matchType = MATCH_TITLE_CONTAINS, pattern = "call")))

    @Test
    fun withMutingOffEveryReminderIsShownEvenForContexts() {
        assertTrue(ContextualReminderPolicy.shouldNotify(onCall, muteContextual = false) { rules })
        assertTrue(ContextualReminderPolicy.shouldNotify(dentist, muteContextual = false) { rules })
    }

    @Test
    fun withMutingOnOnlyContextsAreSilenced() {
        assertFalse(ContextualReminderPolicy.shouldNotify(onCall, muteContextual = true) { rules })
        assertTrue(ContextualReminderPolicy.shouldNotify(dentist, muteContextual = true) { rules })
    }

    @Test
    fun withMutingOnButNoRulesNothingIsSilenced() {
        assertTrue(ContextualReminderPolicy.shouldNotify(onCall, muteContextual = true) { ContextualRuleEvaluator(emptyList()) })
    }

    @Test
    fun theEvaluatorIsNotBuiltWhenMutingIsOff() {
        // building one reads the rules table; reminders for everyone with muting off must not pay that
        var built = false
        ContextualReminderPolicy.shouldNotify(onCall, muteContextual = false) { built = true; rules }
        assertFalse(built)
    }
}
