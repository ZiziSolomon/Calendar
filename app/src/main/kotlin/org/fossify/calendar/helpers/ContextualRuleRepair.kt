package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule

/**
 * Turning CalDAV sync off deletes every synced event; turning it back on re-imports them under new
 * local ids. The evaluator's import_id fallback still finds a marked series afterwards, but an
 * edited occurrence is a child row that only points at its series by local id (parentId), so it
 * silently stops being contextual. Re-keying each mark to the event's current local id after a
 * sync restores the fast path and the parent link in one go.
 */
object ContextualRuleRepair {
    /**
     * Returns the event marks whose stored local id no longer matches the event their import_id
     * names, rewritten with the current id. Marks whose event can't be found are left alone:
     * the calendar may just be unsynced for now, and the mark should apply again when it returns.
     */
    fun rekey(rules: List<ContextualRule>, currentIdForImportId: (String) -> Long?): List<ContextualRule> {
        return rules.mapNotNull { rule ->
            val importId = rule.importId
            if (rule.matchType != MATCH_EVENT_ID || importId.isNullOrEmpty()) {
                return@mapNotNull null
            }

            val currentId = currentIdForImportId(importId) ?: return@mapNotNull null
            if (currentId == rule.eventId) null else rule.copy(eventId = currentId)
        }
    }

    /**
     * The same wipe-and-resync also deletes and recreates a synced calendar under a new local id,
     * so a calendar-scoped rule would silently match nothing. Its repair key is the Android
     * calendar id (CalDAVCalendar.id), which lives in the system calendar provider and survives.
     *
     * Returns the calendar-scoped rules that need saving: re-pointed at the local calendar that now
     * carries their CalDAV id, or, for rules saved before the key existed, given that key from
     * their current calendar. Rules on local calendars, and rules whose calendar isn't synced
     * right now, are left alone.
     */
    fun rekeyCalendars(
        rules: List<ContextualRule>,
        caldavIdOfCalendar: (Long) -> Int?,
        calendarIdForCaldavId: (Int) -> Long?,
    ): List<ContextualRule> {
        return rules.mapNotNull { rule ->
            val calendarId = rule.calendarId ?: return@mapNotNull null
            val caldavId = rule.caldavCalendarId
            if (caldavId == null) {
                // backfill; 0 is how CalendarEntity says "not synced"
                val current = caldavIdOfCalendar(calendarId)?.takeIf { it != 0 } ?: return@mapNotNull null
                rule.copy(caldavCalendarId = current)
            } else {
                val current = calendarIdForCaldavId(caldavId) ?: return@mapNotNull null
                if (current == calendarId) null else rule.copy(calendarId = current)
            }
        }
    }
}
