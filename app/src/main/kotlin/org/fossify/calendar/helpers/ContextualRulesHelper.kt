package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.R
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.contextualRulesDB
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * All rule writes go through here so the cached evaluator is always invalidated; a write that
 * bypassed this would leave every view showing stale contextual-ness until the app restarted.
 * User edits are stamped (updated), keyed for Google (portable keys), remembered when deleted
 * (tombstones), and pushed to the "Subtext rules" calendar by ContextualRulesSync.
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
            synchronized(ContextualRulesSync.lock) {
                rule.caldavCalendarId = withCalendarKey(rule).caldavCalendarId
                val edited = edited(rule)
                rule.updated = edited.updated
                rule.googleCalendarId = edited.googleCalendarId
                rule.googleEventCalendarId = edited.googleEventCalendarId
                rule.googleEventId = edited.googleEventId
                rule.id = dao.insertOrUpdate(rule)
            }
            rulesChanged(shared = true)
            callback?.invoke()
        }
    }

    fun deleteRules(rules: List<ContextualRule>, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            synchronized(ContextualRulesSync.lock) {
                delete(rules)
            }
            rulesChanged(shared = true)
            callback?.invoke()
        }
    }

    // a rule that had reached Google leaves a tombstone, so the next sync deletes it there too
    private fun delete(rules: List<ContextualRule>) {
        if (rules.isEmpty()) return
        dao.deleteRules(rules)
        val now = SharedRulesSync.nowMs()
        val tombstones = rules.filter { it.syncedUpdated != null }.associate { it.uuid to now }
        if (tombstones.isNotEmpty()) context.config.contextualRuleTombstones = context.config.contextualRuleTombstones + tombstones
    }

    // stamped now, with Google keys for whatever local calendar or event it now points at
    private fun edited(rule: ContextualRule): ContextualRule {
        val stamped = rule.copy(updated = SharedRulesSync.nowMs())
        return ContextualRulesSync.withPortableKeys(context, RulesCalendarStore(context), stamped, onlyMissing = false)
    }

    /**
     * Marks each event contextual with a MATCH_EVENT_ID rule. An edited occurrence of a repeating
     * event is stored as a child row, so marks go on its series (whole-series marking, §4 #3).
     */
    fun markEvents(eventIds: Collection<Long>, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            synchronized(ContextualRulesSync.lock) {
                for (eventId in eventIds) {
                    val series = seriesOf(eventId) ?: continue
                    val existing = dao.getRulesForEventId(series.id!!)
                    if (existing.isEmpty()) {
                        dao.insertOrUpdate(
                            edited(
                                ContextualRule(
                                    id = null,
                                    matchType = MATCH_EVENT_ID,
                                    eventId = series.id,
                                    importId = series.importId.ifEmpty { null }
                                )
                            )
                        )
                    } else {
                        existing.filterNot { it.enabled }.forEach { dao.insertOrUpdate(edited(it.copy(enabled = true))) }
                    }
                }
            }
            rulesChanged(shared = true)
            callback?.invoke()
        }
    }

    /** Removes direct marks only; the event can still be contextual through other rules. */
    fun unmarkEvent(eventId: Long, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            val seriesId = seriesOf(eventId)?.id ?: eventId
            synchronized(ContextualRulesSync.lock) {
                delete(dao.getRulesForEventId(seriesId))
            }
            rulesChanged(shared = true)
            callback?.invoke()
        }
    }

    fun unmarkEvents(eventIds: Collection<Long>, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            synchronized(ContextualRulesSync.lock) {
                eventIds.map { seriesOf(it)?.id ?: it }.distinct().forEach { delete(dao.getRulesForEventId(it)) }
            }
            rulesChanged(shared = true)
            callback?.invoke()
        }
    }

    /** Of [eventIds], those directly marked (themselves or through their series). Call from a background thread. */
    fun markedAmong(eventIds: Collection<Long>): Set<Long> {
        val markedSeries = dao.getRules().filter { it.enabled && it.matchType == MATCH_EVENT_ID }.mapNotNull { it.eventId }.toSet()
        return ContextualSelection.markedAmong(eventIds, markedSeries) { seriesOf(it)?.id ?: it }
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
        synchronized(ContextualRulesSync.lock) { repairKeysLocked() }
    }

    private fun repairKeysLocked() {
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

    /**
     * Drops marks on events, and rules on calendars, that the user has just deleted, so they
     * don't linger as "Event (no longer exists)". Never call it for sync-driven removals (see
     * ContextualRuleCleanup). Call from a background thread.
     */
    fun forgetDeleted(eventIds: Collection<Long> = emptyList(), calendarIds: Collection<Long> = emptyList()) {
        val orphans = synchronized(ContextualRulesSync.lock) {
            ContextualRuleCleanup.orphanedBy(dao.getRules(), eventIds, calendarIds).also { delete(it) }
        }
        if (orphans.isNotEmpty()) {
            rulesChanged(shared = true)
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

        synchronized(ContextualRulesSync.lock) {
            plan.toInsert.forEach { dao.insertOrUpdate(edited(withCalendarKey(it))) }
        }
        if (plan.toInsert.isNotEmpty()) {
            rulesChanged(shared = true)
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

    // [shared]: a user's change, which the rules calendar should get now rather than at the next sync
    private fun rulesChanged(shared: Boolean = false) {
        ContextualRulesCache.invalidate()
        context.updateWidgets()
        if (shared) {
            ContextualRulesSync.syncAsync(context)
        }
    }

    /**
     * Why [event] is contextual, as one line for the event screen ("Contextual: title contains
     * “on call”; marked directly"), or null if no enabled rule matches. Reads the saved event,
     * so unsaved edits on screen don't change the answer. Call from a background thread.
     */
    fun explain(event: Event): String? {
        val rules = ContextualRuleEvaluator(dao.getEnabledRules()).matchingRules(event)
        if (rules.isEmpty()) {
            return null
        }

        val res = context.resources
        val reasons = rules.map { rule ->
            // "Event: <this event's own title>" would be circular on its own screen
            val reason = if (rule.matchType == MATCH_EVENT_ID) res.getString(R.string.contextual_reason_marked) else describe(rule)
            val calendar = rule.calendarId?.let { context.calendarsDB.getCalendarWithId(it)?.title }
            if (calendar == null) reason else res.getString(R.string.contextual_reason_in_calendar, reason, calendar)
        }.distinct()
        return res.getString(R.string.contextual_reason, reasons.joinToString("; "))
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
                // a mark from the laptop on an event this phone hasn't got (yet)
                val hint = if (rule.eventId == null && rule.googleEventId != null) rule.sharedJson?.let { SharedRuleCodec.decode(it)?.eventTitle } else null
                if (title == null && hint != null) {
                    res.getString(R.string.contextual_rule_event_unbound, hint)
                } else if (title == null) {
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
