package org.fossify.calendar.helpers

import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events

/**
 * Read-only report of the ids the phone's calendar store exposes, to settle which keys can
 * identify a calendar and an event on both phone and laptop (LAPTOP_PLAN.md, decision 2). The
 * emulator has no Google account, so only a real phone can answer this. Nothing is written.
 */
object SyncDiagnostics {
    private const val EVENTS_PER_CALENDAR = 3
    private const val EXCEPTIONS = 3

    fun report(context: Context): String = buildString {
        try {
            appendCalendars(context)
            appendExceptions(context)
        } catch (e: SecurityException) {
            appendLine("No calendar permission: turn on CalDAV sync first. (${e.message})")
        }
    }

    private fun StringBuilder.appendCalendars(context: Context) {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT, Calendars.NAME, Calendars._SYNC_ID, Calendars.SYNC_EVENTS, Calendars.VISIBLE,
        )
        context.contentResolver.query(Calendars.CONTENT_URI, projection, null, null, Calendars._ID)?.use { cursor ->
            appendLine("CALENDARS (${cursor.count})")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                appendLine()
                appendLine("cal #$id \"${cursor.getString(1)}\"")
                appendLine("  account=${cursor.getString(2)} type=${cursor.getString(3)}")
                appendLine("  owner=${cursor.getString(4)}")
                appendLine("  name=${cursor.getString(5)}")
                appendLine("  _sync_id=${cursor.getString(6)}")
                appendLine("  sync_events=${cursor.getInt(7)} visible=${cursor.getInt(8)}")
                appendEvents(context, id)
            }
        }
    }

    private fun StringBuilder.appendEvents(context: Context, calendarId: Long) {
        val projection = arrayOf(Events._ID, Events.TITLE, Events._SYNC_ID, Events.UID_2445, Events.RRULE)
        // the most recently started events, so the sample reflects how the account syncs now
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0"
        context.contentResolver.query(Events.CONTENT_URI, projection, selection, arrayOf(calendarId.toString()), "${Events.DTSTART} DESC")?.use { cursor ->
            var shown = 0
            while (shown < EVENTS_PER_CALENDAR && cursor.moveToNext()) {
                val title = cursor.getString(1).orEmpty().take(24)
                val repeats = if (cursor.getString(4).isNullOrEmpty()) "" else " (repeats)"
                appendLine("  event #${cursor.getLong(0)} \"$title\"$repeats")
                appendLine("    _sync_id=${cursor.getString(2)}")
                appendLine("    uid_2445=${cursor.getString(3)}")
                shown++
            }
        }
    }

    // edited occurrences of repeating events: their link back to the series is the other key a mark needs
    private fun StringBuilder.appendExceptions(context: Context) {
        val projection = arrayOf(Events._ID, Events.TITLE, Events._SYNC_ID, Events.UID_2445, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_ID)
        val selection = "${Events.ORIGINAL_ID} IS NOT NULL AND ${Events.DELETED} = 0"
        context.contentResolver.query(Events.CONTENT_URI, projection, selection, null, "${Events.DTSTART} DESC")?.use { cursor ->
            appendLine()
            appendLine("EDITED OCCURRENCES (${cursor.count})")
            var shown = 0
            while (shown < EXCEPTIONS && cursor.moveToNext()) {
                appendLine("  event #${cursor.getLong(0)} \"${cursor.getString(1).orEmpty().take(24)}\"")
                appendLine("    _sync_id=${cursor.getString(2)} uid_2445=${cursor.getString(3)}")
                appendLine("    original_sync_id=${cursor.getString(4)} original_id=${cursor.getString(5)}")
                shown++
            }
        }
    }
}
