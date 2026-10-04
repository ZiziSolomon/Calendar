package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.R
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.contextualRulesDB
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.models.ContextualRule
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * All rule writes go through here so the cached evaluator is always invalidated; a write that
 * bypassed this would leave every view showing stale contextual-ness until the app restarted.
 */
class ContextualRulesHelper(val context: Context) {
    private val dao = context.contextualRulesDB

    fun getRules(callback: (ArrayList<ContextualRule>) -> Unit) {
        ensureBackgroundThread {
            callback(ArrayList(dao.getRules()))
        }
    }

    fun saveRule(rule: ContextualRule, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            rule.caldavCalendarId = withCalendarKey(rule).caldavCalendarId
            rule.id = dao.insertOrUpdate(rule)
            rulesChanged()
            callback?.invoke()
        }
    }

    fun deleteRules(rules: List<ContextualRule>, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            dao.deleteRules(rules)
            rulesChanged()
            callback?.invoke()
        }
    }

    /**
     * Marks each event contextual with a MATCH_EVENT_ID rule. An edited occurrence of a repeating
     * event is stored as a child row, so marks go on its series (whole-series marking, §4 #3).
     */
    fun markEvents(eventIds: Collection<Long>, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            for (eventId in eventIds) {
                val series = seriesOf(eventId) ?: continue
                val existing = dao.getRulesForEventId(series.id!!)
                if (existing.isEmpty()) {
                    dao.insertOrUpdate(
                        ContextualRule(
                            id = null,
                            matchType = MATCH_EVENT_ID,
                            eventId = series.id,
                            importId = series.importId.ifEmpty { null }
                        )
                    )
                } else {
                    existing.filterNot { it.enabled }.forEach { dao.insertOrUpdate(it.copy(enabled = true)) }
                }
            }
            rulesChanged()
            callback?.invoke()
        }
    }

    /** Removes direct marks only; the event can still be contextual through other rules. */
    fun unmarkEvent(eventId: Long, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            val seriesId = seriesOf(eventId)?.id ?: eventId
            dao.deleteRules(dao.getRulesForEventId(seriesId))
            rulesChanged()
            callback?.invoke()
        }
    }

    /** Whether the event (or its series) has an enabled direct mark. Call from a background thread. */
    fun isEventMarked(eventId: Long): Boolean {
        val seriesId = seriesOf(eventId)?.id ?: eventId
        return dao.getRulesForEventId(seriesId).any { it.enabled }
    }

    /**
     * Points event marks and calendar-scoped rules back at the current local ids of their events
     * and calendars after a sync may have re-imported them (see ContextualRuleRepair).
     * Call from a background thread.
     */
    fun repairKeys() {
        val eventRepairs = ContextualRuleRepair.rekey(dao.getRules()) { context.eventsDB.getEventIdWithImportId(it) }
        eventRepairs.forEach { dao.insertOrUpdate(it) }

        val calendarRepairs = ContextualRuleRepair.rekeyCalendars(
            rules = dao.getRules(),
            caldavIdOfCalendar = { context.calendarsDB.getCalendarWithId(it)?.caldavCalendarId },
            calendarIdForCaldavId = { context.calendarsDB.getCalendarWithCalDAVCalendarId(it)?.id },
        )
        calendarRepairs.forEach { dao.insertOrUpdate(it) }

        if (eventRepairs.isNotEmpty() || calendarRepairs.isNotEmpty()) {
            rulesChanged()
        }
    }

    /** The rules as one settings-export line (see ContextualRulesBackup). Call from a background thread. */
    fun exportForSettings(): String {
        return ContextualRulesBackup.encode(dao.getRules()) { context.calendarsDB.getCalendarWithId(it)?.title }
    }

    /**
     * Merges rules from a settings import into this install. Returns how many couldn't be bound
     * to a calendar or event here. Call from a background thread.
     */
    fun importFromSettings(json: String): Int {
        val plan = ContextualRulesBackup.planImport(
            entries = ContextualRulesBackup.decode(json),
            existing = dao.getRules(),
            calendarIdForCaldavId = { context.calendarsDB.getCalendarWithCalDAVCalendarId(it)?.id },
            calendarIdForName = { context.calendarsDB.getCalendarIdWithTitle(it) },
            eventIdForImportId = { context.eventsDB.getEventIdWithImportId(it) },
        )

        plan.toInsert.forEach { dao.insertOrUpdate(withCalendarKey(it)) }
        if (plan.toInsert.isNotEmpty()) {
            rulesChanged()
        }
        return plan.skipped
    }

    // a rule scoped to a synced calendar carries that calendar's CalDAV id, so a later
    // wipe-and-resync (new local calendar id) can be repaired
    private fun withCalendarKey(rule: ContextualRule): ContextualRule {
        val caldavId = rule.calendarId?.let { context.calendarsDB.getCalendarWithId(it)?.caldavCalendarId }
        return rule.copy(caldavCalendarId = caldavId?.takeIf { it != 0 })
    }

    private fun seriesOf(eventId: Long) = context.eventsDB.getEventOrTaskWithId(eventId)?.let { event ->
        if (event.parentId != 0L) context.eventsDB.getEventOrTaskWithId(event.parentId) ?: event else event
    }

    private fun rulesChanged() {
        ContextualRulesCache.invalidate()
        context.updateWidgets()
    }

    /** One-line summary for the rules list. Call from a background thread (may read an event). */
    fun describe(rule: ContextualRule): String {
        val res = context.resources
        return when (rule.matchType) {
            MATCH_ALL -> res.getString(R.string.contextual_rule_all_events)
            MATCH_TITLE_CONTAINS -> res.getString(R.string.contextual_rule_title_contains, rule.pattern)
            MATCH_TITLE_REGEX -> res.getString(R.string.contextual_rule_title_regex, rule.pattern)
            MATCH_DURATION_OVER -> res.getString(R.string.contextual_rule_duration_over, formatDuration(rule.pattern))
            MATCH_EVENT_ID -> {
                val title = rule.eventId?.let { context.eventsDB.getEventWithId(it)?.title }
                if (title == null) {
                    res.getString(R.string.contextual_rule_event_missing)
                } else {
                    res.getString(R.string.contextual_rule_event, title)
                }
            }

            else -> ""
        }
    }

    private fun formatDuration(pattern: String): String {
        val minutes = pattern.trim().toLongOrNull() ?: return pattern
        val (amount, unit) = ContextualRuleText.durationParts(minutes)
        val pluralsId = when (unit) {
            ContextualRuleText.Unit.DAYS -> org.fossify.commons.R.plurals.days
            ContextualRuleText.Unit.HOURS -> org.fossify.commons.R.plurals.hours
            ContextualRuleText.Unit.MINUTES -> org.fossify.commons.R.plurals.minutes
        }
        return context.resources.getQuantityString(pluralsId, amount.toInt(), amount.toInt())
    }
}
