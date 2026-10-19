package org.fossify.calendar.helpers

import android.accounts.Account
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import org.fossify.calendar.helpers.SharedRulesSync.CalendarCandidate
import org.fossify.calendar.helpers.SharedRulesSync.EventCandidate
import org.fossify.calendar.helpers.SharedRulesSync.RemoteEvent

/**
 * The phone's calendar store, as far as shared rules need it: the "Subtext rules" calendar's
 * events, and the Google ids (portable keys) of other calendars and events. Writes go in as an
 * ordinary app, so Google's sync adapter uploads them like any other edit.
 * Every call needs the calendar permissions; call from a background thread.
 */
@SuppressLint("MissingPermission")
class RulesCalendarStore(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Every calendar named "Subtext rules", whoever owns it. */
    fun rulesCalendars(): List<CalendarCandidate> {
        val projection = arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.SYNC_EVENTS)
        val result = ArrayList<CalendarCandidate>()
        resolver.query(
            Calendars.CONTENT_URI, projection, "${Calendars.CALENDAR_DISPLAY_NAME} = ?", arrayOf(SharedRulesSync.RULES_CALENDAR_NAME), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    CalendarCandidate(
                        id = cursor.getLong(0),
                        accountName = cursor.getString(1).orEmpty(),
                        accountType = cursor.getString(2).orEmpty(),
                        accessLevel = cursor.getInt(3),
                        syncEvents = cursor.getInt(4) != 0,
                    )
                )
            }
        }
        return result
    }

    fun readEvents(calendarId: Long): List<RemoteEvent> {
        val result = ArrayList<RemoteEvent>()
        resolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID, Events.DESCRIPTION), "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result.add(RemoteEvent(cursor.getLong(0), cursor.getString(1)))
            }
        }
        return result
    }

    /** A free, all-day, reminder-less event on 2000-01-01, like the laptop writes. Returns its id. */
    fun insertEvent(calendarId: Long, title: String, description: String): Long? {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, description)
            put(Events.DTSTART, SharedRulesSync.RULE_DAY_START_MS)
            put(Events.DTEND, SharedRulesSync.RULE_DAY_END_MS)
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
            put(Events.HAS_ALARM, 0)
        }
        return resolver.insert(Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
    }

    fun updateEvent(eventId: Long, title: String, description: String): Boolean {
        val values = ContentValues().apply {
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, description)
        }
        return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null) > 0
    }

    fun deleteEvent(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    /** Turns on syncing for the rules calendar (Calendars.SYNC_EVENTS is app-writable). */
    fun enableSync(calendar: CalendarCandidate): Boolean {
        val values = ContentValues().apply {
            put(Calendars.SYNC_EVENTS, 1)
        }
        val changed = try {
            resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id), values, null, null) > 0
        } catch (e: Exception) {
            false
        }
        requestSync(calendar)
        return changed && rulesCalendars().any { it.id == calendar.id && it.syncEvents }
    }

    /** Asks the account's sync adapter to upload and download now, instead of on its own schedule. */
    fun requestSync(calendar: CalendarCandidate) {
        if (calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) return
        val extras = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true) }
        try {
            ContentResolver.requestSync(Account(calendar.accountName, calendar.accountType), CalendarContract.AUTHORITY, extras)
        } catch (e: Exception) {
        }
    }

    // --- portable keys (LAPTOP_PLAN.md decision 2)

    /** The Google calendar id of an Android calendar: its OWNER_ACCOUNT. */
    fun googleCalendarId(androidCalendarId: Int): String? {
        resolver.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, androidCalendarId.toLong()), arrayOf(Calendars.OWNER_ACCOUNT),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                // for a non-Google account this is still a stable name: the rule round-trips, and
                // simply matches nothing on the laptop, which only sees Google calendars
                return cursor.getString(0)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** Android calendar ids whose Google id is [googleId] (one per account it's subscribed in). */
    fun androidCalendarIds(googleId: String): List<Int> {
        val result = ArrayList<Int>()
        resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), "${Calendars.OWNER_ACCOUNT} = ?", arrayOf(googleId), null)?.use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getInt(0))
        }
        return result
    }

    /** (Google calendar id, series id) of an Android event, or null before Google has given it an id. */
    fun eventKey(androidEventId: Long): Pair<String, String>? {
        resolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, androidEventId), arrayOf(Events.CALENDAR_ID, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val calendar = googleCalendarId(cursor.getInt(0)) ?: return null
                val series = SharedRulesSync.seriesKey(cursor.getString(1), cursor.getString(2)) ?: return null
                return calendar to series
            }
        }
        return null
    }

    /** Events in [androidCalendarIds] whose sync id is [seriesId] or a split of it (`<id>_R…`). */
    fun eventCandidates(androidCalendarIds: List<Int>, seriesId: String): List<EventCandidate> {
        if (androidCalendarIds.isEmpty()) return emptyList()
        val result = ArrayList<EventCandidate>()
        val calendars = androidCalendarIds.joinToString(",")
        val escaped = seriesId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        resolver.query(
            Events.CONTENT_URI, arrayOf(Events.CALENDAR_ID, Events._ID, Events._SYNC_ID),
            "${Events.CALENDAR_ID} IN ($calendars) AND ${Events.DELETED} = 0 AND " +
                "(${Events._SYNC_ID} = ? OR ${Events._SYNC_ID} LIKE ? ESCAPE '\\')",
            arrayOf(seriesId, escaped + "\\_R%"), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val syncId = cursor.getString(2) ?: continue
                result.add(EventCandidate(cursor.getInt(0), cursor.getLong(1), syncId))
            }
        }
        return result
    }
}
