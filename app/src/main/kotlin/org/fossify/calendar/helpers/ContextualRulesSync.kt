package org.fossify.calendar.helpers

import android.content.Context
import android.util.Log
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.contextualRulesDB
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.helpers.SharedRulesSync.CalendarCandidate
import org.fossify.calendar.helpers.SharedRulesSync.LocalEntry
import org.fossify.calendar.models.ContextualRule
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALENDAR
import org.fossify.commons.helpers.PERMISSION_WRITE_CALENDAR
import org.fossify.commons.helpers.ensureBackgroundThread
import java.util.UUID

/**
 * Keeps the local rules table in step with the "Subtext rules" Google calendar, through the
 * phone's calendar store (LAPTOP_PLAN.md phase 19). Runs after every CalDAV sync, after each
 * rule edit here, and when the rules screen opens. The decisions are SharedRulesSync's; this
 * reads, applies, and binds Google ids to this phone's local ids.
 *
 * Nothing happens unless the calendar is there and syncing: a missing or unsynced calendar
 * looks empty, and empty must never read as "every rule was deleted".
 */
object ContextualRulesSync {
    private const val TAG = "ContextualRulesSync"
    private const val SETTINGS_TITLE = "Subtext settings"

    /** Held by every rule write and every pass, so an edit never lands mid-merge. */
    val lock = Any()

    enum class State { OFF, NO_PERMISSION, MISSING, NOT_SYNCING, SYNCED, FAILED }

    data class Status(
        val state: State,
        val calendar: CalendarCandidate? = null,
        val pulled: Int = 0,
        val pushed: Int = 0,
        val deleted: Int = 0,
        /** Events in the rules calendar this version can't read. */
        val unreadable: Int = 0,
        /** Rules that stay on this phone: on a calendar or event Google has no id for (yet). */
        val localOnly: Int = 0,
    )

    @Volatile
    var lastStatus: Status? = null
        private set

    fun syncAsync(context: Context, callback: ((Status) -> Unit)? = null) {
        ensureBackgroundThread {
            val status = sync(context)
            callback?.invoke(status)
        }
    }

    /** One pass. Call from a background thread. */
    fun sync(context: Context): Status = synchronized(lock) {
        val status = try {
            pass(context)
        } catch (e: SecurityException) {
            Status(State.NO_PERMISSION)
        } catch (e: Exception) {
            Log.e(TAG, "rules sync failed", e)
            Status(State.FAILED)
        }
        lastStatus = status
        status
    }

    /** Turns on SYNC_EVENTS for the rules calendar, then runs a pass. Call from a background thread. */
    fun enableCalendarSync(context: Context): Status {
        val store = RulesCalendarStore(context)
        try {
            store.rulesCalendars().let(SharedRulesSync::chooseCalendar)?.let { store.enableSync(it) }
        } catch (e: SecurityException) {
        }
        return sync(context)
    }

    private fun pass(context: Context): Status {
        val config = context.config
        if (!config.caldavSync) return Status(State.OFF)
        if (!context.hasPermission(PERMISSION_READ_CALENDAR) || !context.hasPermission(PERMISSION_WRITE_CALENDAR)) {
            return Status(State.NO_PERMISSION)
        }

        val store = RulesCalendarStore(context)
        val dao = context.contextualRulesDB
        SharedRulesSync.uuidsToRenew(dao.getRules()).forEach {
            dao.insertOrUpdate(it.copy(uuid = UUID.randomUUID().toString(), syncedUpdated = null))
        }
        dao.getRules().forEach { rule ->
            val keyed = withPortableKeys(context, store, rule, onlyMissing = true)
            if (keyed != rule) dao.insertOrUpdate(keyed)
        }

        val calendar = SharedRulesSync.chooseCalendar(store.rulesCalendars()) ?: return Status(State.MISSING)
        if (!calendar.syncEvents) return Status(State.NOT_SYNCING, calendar)

        val remote = store.readEvents(calendar.id)
        val local = dao.getRules().map { LocalEntry(it, sharedForm(context, it)) }
        val tombstones = config.contextualRuleTombstones
        val plan = SharedRulesSync.plan(local, remote, tombstones, calendarIsNew = config.rulesCalendarAndroidId != calendar.id)

        plan.deleteRemote.forEach { store.deleteEvent(it) }
        if (plan.deleteLocal.isNotEmpty()) dao.deleteRules(plan.deleteLocal)
        plan.pullNew.forEach { dao.insertOrUpdate(SharedRulesSync.fromShared(it.rule, it.json, null)) }
        plan.pullUpdate.forEach { (rule, pulled) -> dao.insertOrUpdate(SharedRulesSync.fromShared(pulled.rule, pulled.json, rule)) }

        val now = SharedRulesSync.nowMs()
        var pushed = 0
        plan.pushNew.forEach { entry ->
            // a rule from before sharing has updated = 0; it reaches Google now, so that's its time
            val rule = if (entry.rule.updated == 0L) entry.rule.copy(updated = now) else entry.rule
            val shared = entry.shared!!.copy(updated = SharedRulesSync.toIso(rule.updated))
            val json = SharedRuleCodec.encode(shared)
            if (store.insertEvent(calendar.id, SharedRulesSync.summary(shared), json) != null) {
                dao.insertOrUpdate(rule.copy(syncedUpdated = rule.updated, sharedJson = json))
                pushed++
            }
        }
        plan.pushUpdate.forEach { (eventId, entry) ->
            val json = SharedRuleCodec.encode(entry.shared!!)
            if (store.updateEvent(eventId, SharedRulesSync.summary(entry.shared), json)) {
                dao.insertOrUpdate(entry.rule.copy(syncedUpdated = entry.rule.updated, sharedJson = json))
                pushed++
            }
        }

        if (plan.clearTombstones.isNotEmpty()) config.contextualRuleTombstones = tombstones - plan.clearTombstones
        val settingsWritten = syncColorSlots(context, store, calendar, plan.settings)
        config.rulesCalendarAndroidId = calendar.id
        val bound = bindLocalIds(context, store)

        if (plan.changesLocal || bound) {
            ContextualRulesCache.invalidate()
            context.updateWidgets()
        }
        if (plan.changesRemote || settingsWritten) {
            store.requestSync(calendar)
        }

        return Status(
            state = State.SYNCED,
            calendar = calendar,
            pulled = plan.pullNew.size + plan.pullUpdate.size,
            pushed = pushed,
            deleted = plan.deleteLocal.size + plan.deleteRemote.size,
            unreadable = plan.unreadable,
            localOnly = dao.getRules().count { sharedForm(context, it) == null },
        )
    }

    private fun sharedForm(context: Context, rule: ContextualRule): SharedRuleCodec.SharedRule? {
        val calendarName = rule.calendarId?.takeIf { it != SharedRulesSync.UNBOUND_CALENDAR_ID }?.let { context.calendarsDB.getCalendarWithId(it)?.title }
        val eventTitle = rule.eventId?.let { context.eventsDB.getEventOrTaskWithId(it)?.title }
        return SharedRulesSync.toShared(rule, calendarName, eventTitle)
    }

    /** Google's colour slots win; names only this phone has seen are added and written back. */
    private fun syncColorSlots(context: Context, store: RulesCalendarStore, calendar: CalendarCandidate, remote: SharedRulesSync.Settings?): Boolean {
        val local = context.config.contextualColorSlots
        val merged = SharedRulesSync.mergeColorSlots(local, remote?.colorSlots.orEmpty())
        if (merged != local) context.config.contextualColorSlots = merged
        if (merged == remote?.colorSlots || (remote == null && merged.isEmpty())) return false

        val json = SharedRulesSync.encodeSettings(merged, remote?.json)
        return if (remote == null) store.insertEvent(calendar.id, SETTINGS_TITLE, json) != null else store.updateEvent(remote.eventId, SETTINGS_TITLE, json)
    }

    /**
     * Gives [rule] its Google keys from the local calendar or event it points at. With
     * [onlyMissing], keys already known are kept (a rule from Google arrives with them).
     * Without the permission, or before Google has given an event its id, keys stay as they were.
     */
    fun withPortableKeys(context: Context, store: RulesCalendarStore, rule: ContextualRule, onlyMissing: Boolean): ContextualRule {
        var result = rule
        try {
            val calendarId = rule.calendarId
            if (calendarId == null) {
                result = result.copy(googleCalendarId = null)
            } else if (calendarId != SharedRulesSync.UNBOUND_CALENDAR_ID && !(onlyMissing && rule.googleCalendarId != null)) {
                val caldavId = context.calendarsDB.getCalendarWithId(calendarId)?.caldavCalendarId ?: 0
                // a calendar that only lives in this app has no key; the rule stays on this phone
                result = result.copy(googleCalendarId = if (caldavId == 0) null else store.googleCalendarId(caldavId) ?: rule.googleCalendarId)
            }

            if (rule.matchType == MATCH_EVENT_ID && !(onlyMissing && rule.googleEventId != null)) {
                val androidEventId = rule.importId?.let { caldavEventId(it) }
                if (androidEventId == null) {
                    if (rule.eventId != null && rule.importId.isNullOrEmpty()) {
                        // marked a local-only event
                        result = result.copy(googleEventCalendarId = null, googleEventId = null)
                    }
                } else {
                    store.eventKey(androidEventId)?.let { (calendar, series) ->
                        // bound to one part of a series split in Google: the mark still means the whole series
                        val known = rule.googleEventId
                        val key = if (known != null && SharedRulesSync.isSplitOf(series, known)) known else series
                        result = result.copy(googleEventCalendarId = calendar, googleEventId = key)
                    }
                }
            }
        } catch (e: SecurityException) {
            return rule
        }
        return result
    }

    // "Caldav-<android calendar id>-<android event id>" (CalDAVHelper.getCalDAVEventImportId)
    private fun caldavEventId(importId: String): Long? {
        val parts = importId.split("-")
        return if (parts.size == 3 && parts[0] == CALDAV) parts[2].toLongOrNull() else null
    }

    /** Points rules from Google at this phone's calendars and events once those are synced here. */
    private fun bindLocalIds(context: Context, store: RulesCalendarStore): Boolean {
        val dao = context.contextualRulesDB
        val synced = context.config.getSyncedCalendarIdsAsList().toSet()
        var changed = false
        for (rule in dao.getRules()) {
            var bound = rule
            val googleCalendar = rule.googleCalendarId
            if (rule.calendarId == SharedRulesSync.UNBOUND_CALENDAR_ID && googleCalendar != null) {
                val local = store.androidCalendarIds(googleCalendar).sortedByDescending { it in synced }
                    .firstNotNullOfOrNull { context.calendarsDB.getCalendarWithCalDAVCalendarId(it) }
                if (local != null) bound = bound.copy(calendarId = local.id, caldavCalendarId = local.caldavCalendarId)
            }

            val eventCalendar = rule.googleEventCalendarId
            val series = rule.googleEventId
            if (rule.matchType == MATCH_EVENT_ID && rule.eventId == null && eventCalendar != null && series != null) {
                val candidates = store.eventCandidates(store.androidCalendarIds(eventCalendar), series)
                val localIds = candidates.associateWith { context.eventsDB.getEventIdWithImportId("$CALDAV-${it.androidCalendarId}-${it.androidEventId}") }
                val pick = SharedRulesSync.pickEvent(candidates.filter { localIds[it] != null }, series, synced)
                if (pick != null) {
                    bound = bound.copy(eventId = localIds[pick], importId = "$CALDAV-${pick.androidCalendarId}-${pick.androidEventId}")
                }
            }

            if (bound != rule) {
                dao.insertOrUpdate(bound)
                changed = true
            }
        }
        return changed
    }
}
