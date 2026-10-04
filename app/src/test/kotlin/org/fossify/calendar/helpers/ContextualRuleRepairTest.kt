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
}
