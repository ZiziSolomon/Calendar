package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRuleRepairTest {
    private fun mark(id: Long, eventId: Long?, importId: String?) =
        ContextualRule(id = id, matchType = MATCH_EVENT_ID, eventId = eventId, importId = importId)

    // what the emulator showed after sync off/on: Dentist 1 -> 10, Gym class series 8 -> 17
    private val afterResync = mapOf("Caldav-1-1" to 10L, "Caldav-1-8" to 17L)

    @Test
    fun staleIdsAreRekeyedFromImportId() {
        val rules = listOf(mark(4, 1, "Caldav-1-1"), mark(5, 8, "Caldav-1-8"))
        val repaired = ContextualRuleRepair.rekey(rules) { afterResync[it] }
        assertEquals(listOf(10L, 17L), repaired.map { it.eventId })
        // same rows, so saving them updates instead of inserting
        assertEquals(listOf(4L, 5L), repaired.map { it.id })
        assertEquals(listOf("Caldav-1-1", "Caldav-1-8"), repaired.map { it.importId })
    }

    @Test
    fun upToDateMarksAreNotRewritten() {
        val rules = listOf(mark(4, 10, "Caldav-1-1"))
        assertTrue(ContextualRuleRepair.rekey(rules) { afterResync[it] }.isEmpty())
    }

    @Test
    fun marksWhoseEventIsGoneAreKeptAsTheyAre() {
        // calendar unsynced, or the event deleted upstream: don't touch, it may come back
        val rules = listOf(mark(4, 1, "Caldav-9-9"))
        assertTrue(ContextualRuleRepair.rekey(rules) { afterResync[it] }.isEmpty())
    }

    @Test
    fun localEventMarksAndOtherRuleTypesAreIgnored() {
        val rules = listOf(
            mark(1, 3, null),
            mark(2, 3, ""),
            // a title rule never has an import id, but make sure one wouldn't be "repaired" anyway
            ContextualRule(id = 3, matchType = MATCH_TITLE_CONTAINS, pattern = "shift", importId = "Caldav-1-1")
        )
        val lookups = mutableListOf<String>()
        val repaired = ContextualRuleRepair.rekey(rules) { lookups.add(it); afterResync[it] }
        assertTrue(repaired.isEmpty())
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun aMarkWithNoLocalIdGetsOne() {
        val repaired = ContextualRuleRepair.rekey(listOf(mark(4, null, "Caldav-1-1"))) { afterResync[it] }
        assertEquals(10L, repaired.single().eventId)
    }

    // --- calendar-scoped rules ---

    private fun scoped(id: Long, calendarId: Long?, caldavId: Int?) =
        ContextualRule(id = id, calendarId = calendarId, matchType = MATCH_ALL, caldavCalendarId = caldavId)

    // what the emulator showed: Family (Android calendar 1) was local calendar 2, then 3 after
    // sync off/on; calendar 1 is the app's local "Regular event" calendar
    private val caldavIdByLocalId = mapOf(1L to 0, 3L to 1)
    private val localIdByCaldavId = mapOf(1 to 3L)

    private fun rekeyCalendars(vararg rules: ContextualRule) =
        ContextualRuleRepair.rekeyCalendars(rules.toList(), { caldavIdByLocalId[it] }, { localIdByCaldavId[it] })

    @Test
    fun calendarRuleFollowsItsCalendarToTheNewLocalId() {
        val repaired = rekeyCalendars(scoped(7, 2, 1)).single()
        assertEquals(3L, repaired.calendarId)
        assertEquals(1, repaired.caldavCalendarId)
        assertEquals(7L, repaired.id)
    }

    @Test
    fun upToDateCalendarRuleIsNotRewritten() {
        assertTrue(rekeyCalendars(scoped(7, 3, 1)).isEmpty())
    }

    @Test
    fun calendarRuleIsKeptWhileItsCalendarIsUnsynced() {
        // sync is off: Family is gone, so there's nothing to point at yet
        val repaired = ContextualRuleRepair.rekeyCalendars(listOf(scoped(7, 2, 1)), { null }, { null })
        assertTrue(repaired.isEmpty())
    }

    @Test
    fun ruleSavedBeforeTheKeyExistedIsBackfilled() {
        // a v12 rule on the synced calendar, upgraded to v13 with no CalDAV id yet
        val repaired = rekeyCalendars(scoped(7, 3, null)).single()
        assertEquals(1, repaired.caldavCalendarId)
        assertEquals(3L, repaired.calendarId)
    }

    @Test
    fun rulesOnLocalCalendarsOrEveryCalendarAreLeftAlone() {
        // calendar 1 is local (CalDAV id 0); a null calendar means "all calendars"
        assertTrue(rekeyCalendars(scoped(7, 1, null), scoped(8, null, null)).isEmpty())
    }

    @Test
    fun backfillSkipsACalendarThatNoLongerExists() {
        // v12 rule whose calendar was already wiped before upgrading: nothing to learn the key from
        assertTrue(rekeyCalendars(scoped(7, 2, null)).isEmpty())
    }
}
