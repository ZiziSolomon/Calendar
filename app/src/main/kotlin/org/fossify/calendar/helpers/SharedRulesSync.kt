package org.fossify.calendar.helpers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.fossify.calendar.helpers.SharedRuleCodec.SharedRule
import org.fossify.calendar.models.ContextualRule
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * The pure half of sharing rules through the "Subtext rules" Google calendar (LAPTOP_PLAN.md,
 * decision 1): what to pull, push and delete, given the phone's rules and the calendar's events.
 * The Android half (ContextualRulesSync, RulesCalendarStore) only reads and applies.
 *
 * Google is the truth; the local table is its cache. When both sides changed a rule, the newer
 * `updated` wins (a tie goes to Google). The laptop app works the same way, with Google's etags
 * guarding its writes.
 */
object SharedRulesSync {
    /** Same name the laptop app creates and looks for (rulesCalendar.ts). */
    const val RULES_CALENDAR_NAME = "Subtext rules"

    /** CalendarContract.Calendars.CAL_ACCESS_OWNER; only the account that owns it writes rules. */
    const val CAL_ACCESS_OWNER = 700

    /**
     * Local calendar id of a calendar-scoped rule whose Google calendar isn't synced in this app:
     * no event has it, so the rule matches nothing until the calendar turns up.
     */
    const val UNBOUND_CALENDAR_ID = -1L

    /** Rule events sit on 2000-01-01, all day, like the laptop's (rulesCalendar.ts RULE_DAY). */
    const val RULE_DAY_START_MS = 946_684_800_000L
    const val RULE_DAY_END_MS = RULE_DAY_START_MS + 86_400_000L

    private val TYPE_NAMES = mapOf(
        MATCH_ALL to "all",
        MATCH_TITLE_CONTAINS to "title_contains",
        MATCH_TITLE_REGEX to "title_regex",
        MATCH_DURATION_OVER to "duration_over",
        MATCH_EVENT_ID to "event",
    )

    fun typeName(matchType: Int): String? = TYPE_NAMES[matchType]

    fun matchType(name: String): Int? = TYPE_NAMES.entries.firstOrNull { it.value == name }?.key

    // --- time: whole seconds, as the laptop writes them ("2026-10-05T09:30:00Z")

    fun nowMs(currentMs: Long = System.currentTimeMillis()) = currentMs / 1000 * 1000

    fun toIso(ms: Long): String = Instant.ofEpochSecond(Math.floorDiv(ms, 1000L)).toString()

    fun fromIso(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: Exception) {
        try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    // --- which calendar

    data class CalendarCandidate(
        val id: Long,
        val accountName: String,
        val accountType: String,
        val accessLevel: Int,
        val syncEvents: Boolean,
    )

    /**
     * The rules calendar among those named [RULES_CALENDAR_NAME]: one the account owns (a copy
     * shared into another account is read-only there), synced ones first, then the oldest.
     */
    fun chooseCalendar(candidates: List<CalendarCandidate>): CalendarCandidate? =
        candidates.filter { it.accessLevel >= CAL_ACCESS_OWNER }
            .sortedWith(compareByDescending<CalendarCandidate> { it.syncEvents }.thenBy { it.id })
            .firstOrNull()

    fun isRulesCalendarName(displayName: String?) = displayName == RULES_CALENDAR_NAME

    // --- which event a mark means on this phone

    data class EventCandidate(val androidCalendarId: Int, val androidEventId: Long, val syncId: String)

    /**
     * A mark's key is a series id; Google's "this and following" splits leave `<id>_R<date>`
     * (decision 2). The exact id wins over a split, then a calendar this app syncs, then the oldest.
     */
    fun pickEvent(candidates: List<EventCandidate>, key: String, syncedCalendars: Set<Int>): EventCandidate? =
        candidates.filter { it.syncId == key || isSplitOf(it.syncId, key) }
            .sortedWith(
                compareByDescending<EventCandidate> { it.syncId == key }
                    .thenByDescending { it.androidCalendarId in syncedCalendars }
                    .thenBy { it.androidEventId }
            )
            .firstOrNull()

    fun isSplitOf(syncId: String, key: String) = syncId.startsWith(key + "_R")

    /** An edited occurrence names its series in ORIGINAL_SYNC_ID; a mark keys the series. */
    fun seriesKey(syncId: String?, originalSyncId: String?): String? =
        originalSyncId?.takeIf { it.isNotBlank() } ?: syncId?.takeIf { it.isNotBlank() }

    // --- local rule <-> shared rule

    /**
     * The rule as Google should hold it, or null while it can't be shared: scoped to a calendar
     * or marking an event that has no Google id (a phone-only calendar, an event not uploaded yet).
     * Unknown fields, the format version and name hints come from the last copy seen in Google.
     */
    fun toShared(rule: ContextualRule, calendarName: String? = null, eventTitle: String? = null): SharedRule? {
        val type = typeName(rule.matchType) ?: return null
        if (rule.calendarId != null && rule.googleCalendarId == null) return null
        val isMark = rule.matchType == MATCH_EVENT_ID
        if (isMark && (rule.googleEventCalendarId == null || rule.googleEventId == null)) return null
        if (rule.uuid.isBlank()) return null

        val base = rule.sharedJson?.let { SharedRuleCodec.decode(it) }
        val calendarId = if (rule.calendarId != null) rule.googleCalendarId else null
        return SharedRule(
            version = base?.version ?: SharedRuleCodec.FORMAT_VERSION,
            uuid = rule.uuid,
            updated = toIso(rule.updated),
            enabled = rule.enabled,
            type = type,
            pattern = rule.pattern,
            calendarId = calendarId,
            calendarName = calendarId?.let { calendarName ?: base?.calendarName?.takeIf { base.calendarId == calendarId } },
            eventCalendarId = if (isMark) rule.googleEventCalendarId else null,
            eventId = if (isMark) rule.googleEventId else null,
            eventTitle = if (isMark) eventTitle ?: base?.eventTitle?.takeIf { base.eventId == rule.googleEventId } else null,
            keyName = rule.keyName,
            keyColor = rule.keyColor,
            extra = base?.extra ?: emptyMap(),
        )
    }

    /**
     * The local row for a rule read from Google. Local ids are kept when the Google keys didn't
     * change; otherwise they're cleared ([UNBOUND_CALENDAR_ID] for a calendar) until the caller
     * binds them to what's on this phone.
     */
    fun fromShared(shared: SharedRule, json: String, existing: ContextualRule?): ContextualRule {
        val updated = fromIso(shared.updated) ?: 0L
        val sameCalendar = existing != null && existing.googleCalendarId == shared.calendarId && existing.calendarId != null
        val sameEvent = existing != null && existing.googleEventCalendarId == shared.eventCalendarId && existing.googleEventId == shared.eventId
        val base = existing ?: ContextualRule(id = null)
        return base.copy(
            uuid = shared.uuid,
            updated = updated,
            syncedUpdated = updated,
            enabled = shared.enabled,
            matchType = matchType(shared.type) ?: MATCH_TITLE_CONTAINS,
            pattern = shared.pattern,
            calendarId = when {
                shared.calendarId == null -> null
                sameCalendar -> existing!!.calendarId
                else -> UNBOUND_CALENDAR_ID
            },
            caldavCalendarId = if (shared.calendarId != null && sameCalendar) existing!!.caldavCalendarId else null,
            eventId = if (sameEvent) existing!!.eventId else null,
            importId = if (sameEvent) existing!!.importId else null,
            googleCalendarId = shared.calendarId,
            googleEventCalendarId = shared.eventCalendarId,
            googleEventId = shared.eventId,
            keyName = shared.keyName,
            keyColor = shared.keyColor,
            sharedJson = json,
        )
    }

    /** The readable event title in Google, as the laptop writes it ("Subtext rule: …"). */
    fun summary(rule: SharedRule): String = "Subtext rule: " + when (rule.type) {
        "all" -> "All events"
        "title_contains" -> "Title contains “${rule.pattern}”"
        "title_regex" -> "Title matches /${rule.pattern}/"
        "duration_over" -> {
            val minutes = rule.pattern.trim().toDoubleOrNull()
            when {
                minutes == null -> "Longer than…"
                minutes % 1440 == 0.0 -> "Longer than ${(minutes / 1440).toLong()} day${if (minutes == 1440.0) "" else "s"}"
                else -> "Longer than ${String.format(Locale.US, "%.2f", minutes / 60).trimEnd('0').trimEnd('.')} hours"
            }
        }

        "event" -> "Event: ${rule.eventTitle ?: "one event"}"
        else -> rule.type
    }

    /** Rows whose uuid is blank or repeats an earlier row's (by id); each needs a fresh uuid. */
    fun uuidsToRenew(rules: List<ContextualRule>): List<ContextualRule> {
        val seen = HashSet<String>()
        return rules.sortedBy { it.id ?: Long.MAX_VALUE }.filter { rule -> rule.uuid.isBlank() || !seen.add(rule.uuid) }
    }

    // --- the plan

    data class RemoteEvent(val eventId: Long, val description: String?)

    /** A phone rule with its shared form; [shared] is null for a rule that can't be shared yet. */
    data class LocalEntry(val rule: ContextualRule, val shared: SharedRule?)

    data class Pulled(val eventId: Long, val rule: SharedRule, val json: String, val updatedMs: Long)

    data class Settings(val eventId: Long, val colorSlots: Map<String, Int>, val json: String)

    data class Plan(
        /** Rules in Google that this phone doesn't have yet. */
        val pullNew: List<Pulled> = emptyList(),
        /** Local rule to overwrite with Google's copy (may change its uuid, when adopting a twin). */
        val pullUpdate: List<Pair<ContextualRule, Pulled>> = emptyList(),
        val pushNew: List<LocalEntry> = emptyList(),
        /** Google event id to overwrite with the local rule. */
        val pushUpdate: List<Pair<Long, LocalEntry>> = emptyList(),
        val deleteLocal: List<ContextualRule> = emptyList(),
        val deleteRemote: List<Long> = emptyList(),
        /** Tombstones done with: deleted in Google, outlived by a newer edit, or nothing to delete. */
        val clearTombstones: Set<String> = emptySet(),
        val settings: Settings? = null,
        /** Events in the calendar that aren't rules or settings this version can read; left alone. */
        val unreadable: Int = 0,
    ) {
        val changesLocal get() = pullNew.isNotEmpty() || pullUpdate.isNotEmpty() || deleteLocal.isNotEmpty()
        val changesRemote get() = pushNew.isNotEmpty() || pushUpdate.isNotEmpty() || deleteRemote.isNotEmpty()
    }

    /**
     * @param local every phone rule, uuids unique (see [uuidsToRenew])
     * @param remote every live event in the rules calendar
     * @param tombstones uuid -> when it was deleted on this phone, for rules that had reached Google
     * @param calendarIsNew the rules calendar isn't the one this phone last synced with (first link,
     *   or the calendar was recreated): nothing missing from it counts as deleted there
     */
    fun plan(
        local: List<LocalEntry>,
        remote: List<RemoteEvent>,
        tombstones: Map<String, Long>,
        calendarIsNew: Boolean,
    ): Plan {
        var settings: Settings? = null
        var unreadable = 0
        val decoded = ArrayList<Pulled>()
        for (event in remote.sortedBy { it.eventId }) {
            val text = event.description.orEmpty()
            val slots = decodeSettings(text)
            if (slots != null) {
                // the first (oldest) settings event is the one both apps keep updating
                if (settings == null) settings = Settings(event.eventId, slots, text)
                continue
            }

            val rule = SharedRuleCodec.decode(text)
            if (rule == null) {
                unreadable++
            } else {
                decoded.add(Pulled(event.eventId, rule, text, fromIso(rule.updated) ?: 0L))
            }
        }

        val deleteRemote = ArrayList<Long>()
        // two events for one rule (a write retried, a copy made in Google): the newest stays
        val winners = decoded.groupBy { it.rule.uuid }.values.map { copies ->
            val sorted = copies.sortedWith(compareByDescending<Pulled> { it.updatedMs }.thenBy { it.eventId })
            sorted.drop(1).forEach { deleteRemote.add(it.eventId) }
            sorted.first()
        }.sortedBy { it.eventId }
        val remoteUuids = winners.map { it.rule.uuid }.toSet()

        val localByUuid = local.associateBy { it.rule.uuid }
        val matched = HashSet<String>()
        val pullNew = ArrayList<Pulled>()
        val pullUpdate = ArrayList<Pair<ContextualRule, Pulled>>()
        val pushNew = ArrayList<LocalEntry>()
        val pushUpdate = ArrayList<Pair<Long, LocalEntry>>()
        val deleteLocal = ArrayList<ContextualRule>()
        val clearTombstones = HashSet<String>()

        for (remoteRule in winners) {
            val uuid = remoteRule.rule.uuid
            val entry = localByUuid[uuid]
            val deletedAt = tombstones[uuid]
            if (deletedAt != null) clearTombstones.add(uuid)

            if (entry == null && deletedAt != null) {
                // deleted here; an edit made elsewhere after that brings it back, otherwise it goes
                if (remoteRule.updatedMs > deletedAt) pullNew.add(remoteRule) else deleteRemote.add(remoteRule.eventId)
                continue
            }

            if (entry != null) {
                matched.add(uuid)
                // can't be shared any more (now scoped to a phone-only calendar): leave both copies be
                val shared = entry.shared ?: continue
                val localIsNewer = entry.rule.updated > remoteRule.updatedMs && !remoteRule.rule.isReadOnly
                if (localIsNewer) {
                    pushUpdate.add(remoteRule.eventId to entry)
                } else if (entry.rule.syncedUpdated != remoteRule.updatedMs || !sameContent(shared, remoteRule.rule)) {
                    pullUpdate.add(entry.rule to remoteRule)
                }
                continue
            }

            // first link: a rule made on both devices before they shared becomes one rule
            val twin = local.firstOrNull {
                it.rule.uuid !in matched && it.rule.uuid !in remoteUuids && it.rule.syncedUpdated == null &&
                    it.shared != null && sameMatch(it.shared, remoteRule.rule)
            }
            if (twin != null) {
                matched.add(twin.rule.uuid)
                pullUpdate.add(twin.rule to remoteRule)
            } else {
                pullNew.add(remoteRule)
            }
        }

        // a calendar with nothing in it at all hasn't been downloaded yet; never read that as "all deleted"
        val calendarLooksEmpty = remote.isEmpty()
        for (entry in local) {
            if (entry.rule.uuid in matched || entry.shared == null) continue
            val synced = entry.rule.syncedUpdated
            when {
                synced == null || calendarIsNew -> pushNew.add(entry)
                // deleted in Google, but edited here since it was last in sync: keep the edit
                entry.rule.updated > synced -> pushNew.add(entry)
                calendarLooksEmpty -> Unit
                else -> deleteLocal.add(entry.rule)
            }
        }

        // tombstones for rules Google no longer has: nothing left to delete
        tombstones.keys.filterTo(clearTombstones) { it !in remoteUuids }

        return Plan(pullNew, pullUpdate, pushNew, pushUpdate, deleteLocal, deleteRemote, clearTombstones, settings, unreadable)
    }

    /** Same rule apart from its timestamps and name hints. */
    fun sameContent(a: SharedRule, b: SharedRule) =
        sameMatch(a, b) && a.enabled == b.enabled && a.keyName == b.keyName && a.keyColor == b.keyColor &&
            a.version == b.version && a.extra.mapValues { JsonParser.parseString(it.value) } == b.extra.mapValues { JsonParser.parseString(it.value) }

    /** Matches the same events: type, pattern, calendar and event. */
    fun sameMatch(a: SharedRule, b: SharedRule) =
        a.type == b.type && a.pattern == b.pattern && a.calendarId == b.calendarId &&
            a.eventCalendarId == b.eventCalendarId && a.eventId == b.eventId

    // --- the shared settings event (colour slots), as rulesCalendar.ts writes it

    fun decodeSettings(text: String): Map<String, Int>? {
        val obj = try {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val kind = obj.get("kind")
        if (kind == null || !kind.isJsonPrimitive || kind.asJsonPrimitive.let { !it.isString || it.asString != "settings" }) return null
        val slots = obj.get("colorSlots")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return slots.entrySet().mapNotNull { (name, value) ->
            val number = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return@mapNotNull null
            if (number >= 0 && number == Math.floor(number) && number <= Int.MAX_VALUE) name to number.toInt() else null
        }.toMap()
    }

    /** Writes [slots] into the settings JSON, keeping any other fields a newer app put there. */
    fun encodeSettings(slots: Map<String, Int>, previous: String? = null): String {
        val out = previous?.let {
            try {
                JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject
            } catch (e: Exception) {
                null
            }
        } ?: JsonObject()
        out.addProperty("kind", "settings")
        if (!out.has("v")) out.addProperty("v", 1)
        out.add("colorSlots", JsonObject().apply { slots.toSortedMap().forEach { (name, slot) -> addProperty(name, slot) } })
        return out.toString()
    }

    /** Google's slots win; names only this phone has seen are added, so both show one colour per context. */
    fun mergeColorSlots(local: Map<String, Int>, remote: Map<String, Int>): Map<String, Int> =
        remote + local.filterKeys { it !in remote }
}
