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
}
