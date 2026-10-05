package org.fossify.calendar.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualColorsTest {
    @Test
    fun firstContextTakesTheFirstSlot() {
        val (slot, updated) = ContextualColors.assign(emptyMap(), "On call")
        assertEquals(0, slot)
        assertEquals(mapOf("on call" to 0), updated)
    }

    @Test
    fun knownContextKeepsItsSlotAndNeedsNoWrite() {
        val (slot, updated) = ContextualColors.assign(mapOf("on call" to 3), "On call")
        assertEquals(3, slot)
        assertNull(updated)
    }

    @Test
    fun titlesMatchIgnoringCaseAndSpaces() {
        assertEquals(2, ContextualColors.assign(mapOf("on call" to 2), "  ON CALL ").first)
    }

    @Test
    fun newContextsNeverShareASlotWhileTheyLast() {
        var slots = emptyMap<String, Int>()
        val assigned = listOf("On call", "Early shift", "Kids weekend", "Gym").map { title ->
            val (slot, updated) = ContextualColors.assign(slots, title)
            slots = updated ?: slots
            slot
        }
        assertEquals(listOf(0, 1, 2, 3), assigned)
    }

    @Test
    fun aGapLeftByAnImportedMapIsFilledFirst() {
        assertEquals(1, ContextualColors.assign(mapOf("a" to 0, "b" to 2), "c").first)
    }

    @Test
    fun pastThePaletteSlotsRepeatInOrder() {
        val full = mapOf("a" to 0, "b" to 1, "c" to 2)
        assertEquals(0, ContextualColors.assign(full, "d", paletteSize = 3).first)
        assertEquals(1, ContextualColors.assign(full + ("d" to 0), "e", paletteSize = 3).first)
    }

    @Test
    fun paletteColoursAreDistinct() {
        assertEquals(ContextualColors.PALETTE.size, ContextualColors.PALETTE.toSet().size)
    }
}
