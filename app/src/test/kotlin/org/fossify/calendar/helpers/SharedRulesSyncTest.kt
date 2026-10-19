package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.SharedRuleCodec.SharedRule
import org.fossify.calendar.helpers.SharedRulesSync.CalendarCandidate
import org.fossify.calendar.helpers.SharedRulesSync.EventCandidate
import org.fossify.calendar.helpers.SharedRulesSync.LocalEntry
import org.fossify.calendar.helpers.SharedRulesSync.RemoteEvent
import org.fossify.calendar.models.ContextualRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRulesSyncTest {
    private val t1 = SharedRulesSync.fromIso("2026-10-06T10:00:00Z")!!
    private val t2 = SharedRulesSync.fromIso("2026-10-06T11:00:00Z")!!
    private val t3 = SharedRulesSync.fromIso("2026-10-06T12:00:00Z")!!

    private fun local(
        uuid: String, pattern: String = "on call", updated: Long = t1, synced: Long? = t1, id: Long = uuid.hashCode().toLong(),
    ) = ContextualRule(id = id, matchType = MATCH_TITLE_CONTAINS, pattern = pattern, uuid = uuid, updated = updated, syncedUpdated = synced)

    private fun entry(rule: ContextualRule) = LocalEntry(rule, SharedRulesSync.toShared(rule))

    private fun remote(eventId: Long, uuid: String, pattern: String = "on call", updated: Long = t1, extra: String = "") = RemoteEvent(
        eventId,
        """{"v":1,"uuid":"$uuid","updated":"${SharedRulesSync.toIso(updated)}","enabled":true,"match":{"type":"title_contains","pattern":"$pattern"}$extra}""",
    )

    private fun plan(
        local: List<ContextualRule>, remote: List<RemoteEvent>, tombstones: Map<String, Long> = emptyMap(), calendarIsNew: Boolean = false,
    ) = SharedRulesSync.plan(local.map(::entry), remote, tombstones, calendarIsNew)

    // --- in step

    @Test
    fun nothingToDoWhenBothSidesAgree() {
        val p = plan(listOf(local("a")), listOf(remote(1, "a")))
        assertFalse(p.changesLocal)
        assertFalse(p.changesRemote)
    }

    @Test
    fun aRuleOnlyInGoogleIsPulled() {
        val p = plan(emptyList(), listOf(remote(1, "a")))
        assertEquals(listOf("a"), p.pullNew.map { it.rule.uuid })
    }

    @Test
    fun aNewLocalRuleIsPushed() {
        val p = plan(listOf(local("a", synced = null)), emptyList())
        assertEquals(listOf("a"), p.pushNew.map { it.rule.uuid })
        assertTrue(p.deleteLocal.isEmpty())
    }

    // --- conflicts: the newest edit wins, a tie goes to Google

    @Test
    fun aNewerLocalEditOverwritesGoogle() {
        val p = plan(listOf(local("a", pattern = "phone", updated = t3)), listOf(remote(7, "a", pattern = "laptop", updated = t2)))
        assertEquals(listOf(7L), p.pushUpdate.map { it.first })
        assertTrue(p.pullUpdate.isEmpty())
    }

    @Test
    fun aNewerGoogleEditOverwritesTheLocalOne() {
        val p = plan(listOf(local("a", pattern = "phone", updated = t2)), listOf(remote(7, "a", pattern = "laptop", updated = t3)))
        assertEquals("laptop", p.pullUpdate.single().second.rule.pattern)
        assertTrue(p.pushUpdate.isEmpty())
    }

    @Test
    fun aTieGoesToGoogle() {
        val p = plan(listOf(local("a", pattern = "phone", updated = t2, synced = t1)), listOf(remote(7, "a", pattern = "laptop", updated = t2)))
        assertEquals("laptop", p.pullUpdate.single().second.rule.pattern)
        assertTrue(p.pushUpdate.isEmpty())
    }

    @Test
    fun aRuleFromANewerAppAlwaysTakesGooglesCopy() {
        val newer = RemoteEvent(7, """{"v":2,"uuid":"a","updated":"2026-10-06T10:00:00Z","match":{"type":"title_contains","pattern":"laptop"}}""")
        val p = plan(listOf(local("a", pattern = "phone", updated = t3)), listOf(newer))
        assertTrue(p.pushUpdate.isEmpty())
        assertEquals("laptop", p.pullUpdate.single().second.rule.pattern)
    }

    // --- deletes on either side

    @Test
    fun aRuleDeletedInGoogleIsDeletedHere() {
        val p = plan(listOf(local("a"), local("b")), listOf(remote(1, "b")))
        assertEquals(listOf("a"), p.deleteLocal.map { it.uuid })
    }

    @Test
    fun aRuleDeletedInGoogleButEditedHereSinceIsKeptAndPushed() {
        val p = plan(listOf(local("a", updated = t2, synced = t1), local("b")), listOf(remote(1, "b")))
        assertTrue(p.deleteLocal.isEmpty())
        assertEquals(listOf("a"), p.pushNew.map { it.rule.uuid })
    }

    @Test
    fun aRuleDeletedHereIsDeletedInGoogle() {
        val p = plan(emptyList(), listOf(remote(5, "a", updated = t1)), tombstones = mapOf("a" to t2))
        assertEquals(listOf(5L), p.deleteRemote)
        assertTrue(p.pullNew.isEmpty())
        assertEquals(setOf("a"), p.clearTombstones)
    }

    @Test
    fun anEditInGoogleAfterTheLocalDeleteBringsTheRuleBack() {
        val p = plan(emptyList(), listOf(remote(5, "a", updated = t3)), tombstones = mapOf("a" to t2))
        assertTrue(p.deleteRemote.isEmpty())
        assertEquals(listOf("a"), p.pullNew.map { it.rule.uuid })
        assertEquals(setOf("a"), p.clearTombstones)
    }

    @Test
    fun aTombstoneForARuleGoogleNoLongerHasIsCleared() {
        val p = plan(emptyList(), listOf(remote(1, "b")), tombstones = mapOf("gone" to t1))
        assertEquals(setOf("gone"), p.clearTombstones)
        assertTrue(p.deleteRemote.isEmpty())
    }

    // --- missing or new calendar: never read as "everything was deleted"

    @Test
    fun anEmptyCalendarDeletesNothingHere() {
        val p = plan(listOf(local("a"), local("b")), emptyList())
        assertTrue(p.deleteLocal.isEmpty())
    }

    @Test
    fun aNewCalendarGetsEveryLocalRuleInsteadOfDeletingThem() {
        val p = plan(listOf(local("a"), local("b")), listOf(remote(1, "c")), calendarIsNew = true)
        assertTrue(p.deleteLocal.isEmpty())
        assertEquals(setOf("a", "b"), p.pushNew.map { it.rule.uuid }.toSet())
        assertEquals(listOf("c"), p.pullNew.map { it.rule.uuid })
    }

    // --- malformed JSON and unknown fields

    @Test
    fun unreadableEventsAreCountedAndLeftAlone() {
        val p = plan(
            listOf(local("a")),
            listOf(remote(1, "a"), RemoteEvent(2, "not json {"), RemoteEvent(3, null), RemoteEvent(4, """{"v":1,"uuid":"x","match":{"type":"nope"}}""")),
        )
        assertEquals(3, p.unreadable)
        assertTrue(p.deleteRemote.isEmpty())
        assertFalse(p.changesLocal)
    }

    @Test
    fun unknownFieldsSurviveTheLocalCacheAndAPhoneEdit() {
        val p = plan(emptyList(), listOf(remote(1, "a", extra = ""","future":{"x":[1,2]}""")))
        val pulled = p.pullNew.single()
        val cached = SharedRulesSync.fromShared(pulled.rule, pulled.json, null)
        // edited on the phone later
        val edited = cached.copy(pattern = "shift", updated = t3)
        val written = SharedRuleCodec.encode(SharedRulesSync.toShared(edited)!!)
        assertTrue(written, written.contains(""""future":{"x":[1,2]}"""))
        assertTrue(written.contains(""""pattern":"shift""""))
        assertTrue(written.contains(""""updated":"2026-10-06T12:00:00Z""""))
    }

    @Test
    fun aChangeOnlyInUnknownFieldsIsPulled() {
        val synced = local("a")
        val p = plan(listOf(synced.copy(sharedJson = remote(1, "a").description)), listOf(remote(1, "a", extra = ""","future":true""")))
        assertEquals(1, p.pullUpdate.size)
    }

    // --- duplicates

    @Test
    fun twoEventsForOneRuleKeepTheNewest() {
        val p = plan(emptyList(), listOf(remote(1, "a", pattern = "old", updated = t1), remote(2, "a", pattern = "new", updated = t2)))
        assertEquals(listOf(1L), p.deleteRemote)
        assertEquals("new", p.pullNew.single().rule.pattern)
    }

    @Test
    fun identicalDuplicatesKeepTheOldestEvent() {
        val p = plan(emptyList(), listOf(remote(9, "a"), remote(4, "a")))
        assertEquals(listOf(9L), p.deleteRemote)
        assertEquals(4L, p.pullNew.single().eventId)
    }

    @Test
    fun theSameRuleMadeOnBothDevicesBeforeSharingBecomesOne() {
        val phone = local("phone-uuid", pattern = "on call", updated = 0, synced = null)
        val p = plan(listOf(phone), listOf(remote(1, "laptop-uuid", pattern = "on call")))
        assertTrue(p.pushNew.isEmpty())
        assertTrue(p.pullNew.isEmpty())
        val (rule, pulled) = p.pullUpdate.single()
        assertEquals("phone-uuid", rule.uuid)
        assertEquals("laptop-uuid", SharedRulesSync.fromShared(pulled.rule, pulled.json, rule).uuid)
    }

    @Test
    fun aRuleAlreadySyncedIsNeverMergedIntoAnother() {
        val p = plan(listOf(local("a", pattern = "x")), listOf(remote(1, "a", pattern = "x"), remote(2, "b", pattern = "x")))
        assertEquals(listOf("b"), p.pullNew.map { it.rule.uuid })
    }

    @Test
    fun repeatedLocalUuidsAreRenewedAfterTheFirst() {
        val rules = listOf(local("a", id = 3), local("a", id = 1), local("", id = 2), local("b", id = 4))
        assertEquals(listOf(2L, 3L), SharedRulesSync.uuidsToRenew(rules).map { it.id })
    }

    // --- rules that can't be shared

    @Test
    fun ruleOnAPhoneOnlyCalendarStaysLocal() {
        val rule = local("a", synced = null).copy(calendarId = 5, googleCalendarId = null)
        assertNull(SharedRulesSync.toShared(rule))
        val p = SharedRulesSync.plan(listOf(LocalEntry(rule, null)), emptyList(), emptyMap(), false)
        assertTrue(p.pushNew.isEmpty())
        assertTrue(p.deleteLocal.isEmpty())
    }

    @Test
    fun aMarkWithoutAGoogleIdYetStaysLocal() {
        val mark = ContextualRule(id = 1, matchType = MATCH_EVENT_ID, eventId = 9, importId = "Caldav-3-9")
        assertNull(SharedRulesSync.toShared(mark))
        val keyed = mark.copy(googleEventCalendarId = "me@gmail.com", googleEventId = "series1")
        assertEquals("series1", SharedRulesSync.toShared(keyed, eventTitle = "Dentist")!!.eventId)
    }

    // --- local <-> shared

    @Test
    fun aCalendarRuleFromGoogleMatchesNothingUntilItsCalendarIsHere() {
        val shared = SharedRule(uuid = "a", updated = "2026-10-06T10:00:00Z", type = "all", calendarId = "fam@group.calendar.google.com", calendarName = "Family")
        val rule = SharedRulesSync.fromShared(shared, SharedRuleCodec.encode(shared), null)
        assertEquals(SharedRulesSync.UNBOUND_CALENDAR_ID, rule.calendarId)
        assertEquals(MATCH_ALL, rule.matchType)
        assertEquals(t1, rule.syncedUpdated)
        // and it goes back to Google unchanged, name hint included
        assertEquals(shared, SharedRulesSync.toShared(rule))
    }

    @Test
    fun aPullKeepsTheLocalBindingWhenTheGoogleKeyIsUnchanged() {
        val bound = local("a").copy(calendarId = 12, caldavCalendarId = 4, googleCalendarId = "work@x")
        val shared = SharedRulesSync.toShared(bound)!!.copy(pattern = "new", updated = "2026-10-06T12:00:00Z")
        val pulled = SharedRulesSync.fromShared(shared, SharedRuleCodec.encode(shared), bound)
        assertEquals(12L, pulled.calendarId)
        assertEquals(bound.id, pulled.id)
        assertEquals("new", pulled.pattern)
        val moved = SharedRulesSync.fromShared(shared.copy(calendarId = "other@x"), "{}", bound)
        assertEquals(SharedRulesSync.UNBOUND_CALENDAR_ID, moved.calendarId)
        assertNull(moved.caldavCalendarId)
    }

    @Test
    fun matchTypesMapBothWays() {
        listOf(MATCH_ALL, MATCH_TITLE_CONTAINS, MATCH_TITLE_REGEX, MATCH_DURATION_OVER, MATCH_EVENT_ID).forEach {
            assertEquals(it, SharedRulesSync.matchType(SharedRulesSync.typeName(it)!!))
        }
    }

    @Test
    fun summariesReadLikeTheLaptops() {
        fun s(type: String, pattern: String = "", title: String? = null) =
            SharedRulesSync.summary(SharedRule(uuid = "u", updated = "", type = type, pattern = pattern, eventTitle = title))
        assertEquals("Subtext rule: Title contains “on call”", s("title_contains", "on call"))
        assertEquals("Subtext rule: Longer than 2 days", s("duration_over", "2880"))
        assertEquals("Subtext rule: Longer than 1.5 hours", s("duration_over", "90"))
        assertEquals("Subtext rule: Event: Dentist", s("event", title = "Dentist"))
    }

    @Test
    fun timesAreWholeSecondsInUtc() {
        assertEquals("2026-10-06T10:00:00Z", SharedRulesSync.toIso(t1 + 999))
        assertEquals(t1, SharedRulesSync.fromIso("2026-10-06T11:00:00+01:00"))
        assertNull(SharedRulesSync.fromIso("yesterday"))
        assertEquals(1_000L, SharedRulesSync.nowMs(1_999L))
    }

    // --- choosing the calendar and the event

    @Test
    fun theOwnedSyncedOldestCalendarIsTheRulesCalendar() {
        val shared = CalendarCandidate(1, "other@gmail.com", "com.google", 200, true)
        val unsynced = CalendarCandidate(2, "me@gmail.com", "com.google", 700, false)
        val synced = CalendarCandidate(5, "me@gmail.com", "com.google", 700, true)
        val alsoSynced = CalendarCandidate(9, "me2@gmail.com", "com.google", 800, true)
        assertEquals(5L, SharedRulesSync.chooseCalendar(listOf(shared, unsynced, alsoSynced, synced))!!.id)
        assertEquals(2L, SharedRulesSync.chooseCalendar(listOf(shared, unsynced))!!.id)
        assertNull(SharedRulesSync.chooseCalendar(listOf(shared)))
        assertNull(SharedRulesSync.chooseCalendar(emptyList()))
    }

    @Test
    fun aMarkFindsItsSeriesBeforeASplitAndPrefersSyncedCalendars() {
        val split = EventCandidate(3, 20, "abc_R20270201T090000")
        val inOtherAccount = EventCandidate(4, 10, "abc")
        val exact = EventCandidate(3, 30, "abc")
        val unrelated = EventCandidate(3, 5, "abcd")
        assertEquals(30L, SharedRulesSync.pickEvent(listOf(split, inOtherAccount, exact, unrelated), "abc", setOf(3))!!.androidEventId)
        assertEquals(20L, SharedRulesSync.pickEvent(listOf(split, unrelated), "abc", setOf(3))!!.androidEventId)
        assertNull(SharedRulesSync.pickEvent(listOf(unrelated), "abc", setOf(3)))
    }

    @Test
    fun anEditedOccurrenceKeysItsSeries() {
        assertEquals("abc", SharedRulesSync.seriesKey("abc_20261006T090000Z", "abc"))
        assertEquals("abc", SharedRulesSync.seriesKey("abc", null))
        assertNull(SharedRulesSync.seriesKey(null, ""))
    }

    // --- shared settings (colour slots)

    @Test
    fun theSettingsEventIsReadAsTheLaptopWritesIt() {
        val p = plan(emptyList(), listOf(RemoteEvent(3, """{"kind":"settings","v":1,"colorSlots":{"on call":2,"bad":-1,"half":1.5}}"""), remote(1, "a")))
        assertEquals(mapOf("on call" to 2), p.settings!!.colorSlots)
        assertEquals(0, p.unreadable)
        assertEquals(1, p.pullNew.size)
    }

    @Test
    fun settingsKeepOtherFieldsAndSortSlots() {
        val written = SharedRulesSync.encodeSettings(mapOf("b" to 1, "a" to 0), """{"kind":"settings","v":1,"colorSlots":{},"theme":"dark"}""")
        assertEquals("""{"kind":"settings","v":1,"colorSlots":{"a":0,"b":1},"theme":"dark"}""", written)
        assertEquals(mapOf("a" to 0, "b" to 1), SharedRulesSync.decodeSettings(written))
        assertNull(SharedRulesSync.decodeSettings("""{"v":1,"uuid":"a","match":{"type":"all"}}"""))
        assertNotNull(SharedRulesSync.decodeSettings(SharedRulesSync.encodeSettings(emptyMap())))
    }

    @Test
    fun googlesColourSlotsWinAndPhoneOnlyNamesAreAdded() {
        assertEquals(mapOf("a" to 1, "b" to 2), SharedRulesSync.mergeColorSlots(mapOf("a" to 0, "b" to 2), mapOf("a" to 1)))
    }
}
