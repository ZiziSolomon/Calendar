package org.fossify.calendar.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fossify.calendar.helpers.MATCH_TITLE_CONTAINS
import java.util.UUID

/**
 * A rule marking matching events as "contextual" (ambient background, not a commitment).
 * An event is contextual if any enabled rule matches it.
 *
 * Rules deliberately live outside the `events` table: CalDAV sync rewrites event rows with
 * REPLACE whenever the upstream event changes, which would wipe any per-event local flag.
 * See docs/CONTEXTUAL_EVENTS.md §0.2.
 */
@Entity(tableName = "contextual_rules")
data class ContextualRule(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    // null = applies to events from every calendar
    @ColumnInfo(name = "calendar_id") var calendarId: Long? = null,
    @ColumnInfo(name = "match_type") var matchType: Int = MATCH_TITLE_CONTAINS,
    // Meaning depends on matchType: substring, regex, or minimum duration in minutes
    @ColumnInfo(name = "pattern") var pattern: String = "",
    // MATCH_EVENT_ID only. eventId is the fast key; importId is a repair key that survives
    // a full wipe-and-resync where local ids get reassigned. Local-only events have no importId.
    @ColumnInfo(name = "event_id") var eventId: Long? = null,
    @ColumnInfo(name = "import_id") var importId: String? = null,
    @ColumnInfo(name = "enabled") var enabled: Boolean = true,
    // Repair key for calendarId, like importId for eventId: the Android calendar id of a synced
    // calendar, which survives the calendar being re-imported under a new local id. Null for
    // local calendars and for rules on every calendar. Added in DB v13.
    @ColumnInfo(name = "caldav_calendar_id") var caldavCalendarId: Int? = null,
    // How matching events appear in the key and grid (Phase 16): the name the key shows instead
    // of the event title, and a fixed colour instead of the automatic one. Either, both or
    // neither; when several rules match, the most specific one with a value wins
    // (ContextualDisplay). Added in DB v14.
    @ColumnInfo(name = "key_name") var keyName: String? = null,
    @ColumnInfo(name = "key_color") var keyColor: Int? = null,
    // Shared through the "Subtext rules" Google calendar (LAPTOP_PLAN.md, phase 19). Added in DB
    // v15. The uuid names the rule on every device; updated is the last edit (epoch ms, whole
    // seconds), and the newest edit wins when the phone and the laptop disagree.
    @ColumnInfo(name = "uuid", defaultValue = "''") var uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "updated", defaultValue = "0") var updated: Long = 0,
    // The `updated` Google last agreed to; null = never in Google. Tells a rule deleted in
    // Google (synced before, gone now) from one not uploaded yet.
    @ColumnInfo(name = "synced_updated") var syncedUpdated: Long? = null,
    // Portable keys (decision 2): the Google calendar id for calendar-scoped rules, and the
    // Google calendar + series event id for marks. Local ids above are bound from these on
    // each sync; null while unknown (a phone-only calendar or event can't be shared).
    @ColumnInfo(name = "google_calendar_id") var googleCalendarId: String? = null,
    @ColumnInfo(name = "google_event_calendar_id") var googleEventCalendarId: String? = null,
    @ColumnInfo(name = "google_event_id") var googleEventId: String? = null,
    // The rule's JSON as last read from or written to Google: keeps fields from newer app
    // versions, the format version, and the calendar/event name hints.
    @ColumnInfo(name = "shared_json") var sharedJson: String? = null
)
