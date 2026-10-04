package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule

/**
 * Which rules a user's deletion leaves pointing at nothing. Only call this for deletions the
 * user asked for: a sync can delete and re-import the same events and calendars (sync off/on),
 * and their marks and rules must survive that to be re-keyed by ContextualRuleRepair.
 */
object ContextualRuleCleanup {
    fun orphanedBy(
        rules: List<ContextualRule>,
        deletedEventIds: Collection<Long> = emptyList(),
        deletedCalendarIds: Collection<Long> = emptyList(),
    ): List<ContextualRule> {
        val eventIds = deletedEventIds.toHashSet()
        val calendarIds = deletedCalendarIds.toHashSet()
        return rules.filter { rule ->
            (rule.matchType == MATCH_EVENT_ID && rule.eventId in eventIds) ||
                (rule.calendarId != null && rule.calendarId in calendarIds)
        }
    }
}
