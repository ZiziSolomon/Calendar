package org.fossify.calendar.helpers

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.ContextualRuleBackupEntry as Entry

/**
 * Carries contextual rules through Fossify's "Export settings" file, which is otherwise key=value
 * prefs only, so a reinstall or a phone move would silently lose every rule. The rules travel
 * as one JSON line under CONTEXTUAL_RULES.
 *
 * Local ids mean nothing in another install, so each rule carries its portable keys instead:
 * - calendar-scoped rules: the CalDAV calendar id (same device, reinstalled) and the calendar's
 *   name (fallback, e.g. a new phone with the same Google calendars);
 * - event marks: the event's import_id. For a synced event that id embeds the Android calendar
 *   provider's ids, so it resolves after a reinstall on the same phone but not on a new one.
 *   Marks on local-only events have no import_id and can't travel at all.
 */
object ContextualRulesBackup {
    data class ImportPlan(val toInsert: List<ContextualRule>, val skipped: Int)

    private val gson = Gson()
    private val entryListType = object : TypeToken<List<Entry>>() {}.type

    /** Rules that can't travel (local-only event marks) are left out. */
    fun encode(rules: List<ContextualRule>, calendarNameOf: (Long) -> String?): String {
        val entries = rules.mapNotNull { rule ->
            if (rule.matchType == MATCH_EVENT_ID && rule.importId.isNullOrEmpty()) {
                return@mapNotNull null
            }

            Entry(
                matchType = rule.matchType,
                pattern = rule.pattern,
                enabled = rule.enabled,
                calendarName = rule.calendarId?.let(calendarNameOf),
                caldavCalendarId = rule.caldavCalendarId,
                importId = rule.importId?.takeIf { rule.matchType == MATCH_EVENT_ID },
            )
        }
        return gson.toJson(entries)
    }

    /** A damaged or hand-edited line yields no entries rather than failing the whole settings import. */
    fun decode(json: String): List<Entry> {
        val entries: List<Entry?> = try {
            gson.fromJson(json, entryListType) ?: emptyList()
        } catch (e: JsonParseException) {
            emptyList()
        }

        // Gson bypasses Kotlin's null-safety, so a missing "pattern" arrives as null
        return entries.filterNotNull().filter { it.matchType in MATCH_ALL..MATCH_EVENT_ID }
            .map { it.copy(pattern = it.pattern ?: "") }
    }

    /**
     * Merges [entries] into this install. A rule that can't be bound here is skipped rather than
     * widened: a calendar rule whose calendar is missing would otherwise match every calendar.
     * Rules equal to an existing one (after binding) are dropped, so importing twice is harmless.
     */
    fun planImport(
        entries: List<Entry>,
        existing: List<ContextualRule>,
        calendarIdForCaldavId: (Int) -> Long?,
        calendarIdForName: (String) -> Long?,
        eventIdForImportId: (String) -> Long?,
    ): ImportPlan {
        val seen = existing.map { identity(it) }.toMutableSet()
        val toInsert = mutableListOf<ContextualRule>()
        var skipped = 0

        for (entry in entries) {
            val rule = bind(entry, calendarIdForCaldavId, calendarIdForName, eventIdForImportId)
            if (rule == null) {
                skipped++
            } else if (seen.add(identity(rule))) {
                toInsert += rule
            }
        }

        return ImportPlan(toInsert, skipped)
    }

    private fun bind(
        entry: Entry,
        calendarIdForCaldavId: (Int) -> Long?,
        calendarIdForName: (String) -> Long?,
        eventIdForImportId: (String) -> Long?,
    ): ContextualRule? {
        val calendarId = if (entry.calendarName == null && entry.caldavCalendarId == null) {
            null
        } else {
            entry.caldavCalendarId?.let(calendarIdForCaldavId)
                ?: entry.calendarName?.let(calendarIdForName)
                ?: return null
        }

        var eventId: Long? = null
        if (entry.matchType == MATCH_EVENT_ID) {
            val importId = entry.importId?.takeIf { it.isNotEmpty() } ?: return null
            eventId = eventIdForImportId(importId) ?: return null
        }

        return ContextualRule(
            id = null,
            calendarId = calendarId,
            matchType = entry.matchType,
            pattern = entry.pattern,
            eventId = eventId,
            importId = entry.importId.takeIf { entry.matchType == MATCH_EVENT_ID },
            enabled = entry.enabled,
        )
    }

    // what makes two rules "the same rule"; enabled state and repair keys don't count
    private fun identity(rule: ContextualRule): List<Any?> = when (rule.matchType) {
        MATCH_EVENT_ID -> listOf(rule.matchType, rule.eventId)
        else -> listOf(rule.matchType, rule.calendarId, rule.pattern)
    }
}
