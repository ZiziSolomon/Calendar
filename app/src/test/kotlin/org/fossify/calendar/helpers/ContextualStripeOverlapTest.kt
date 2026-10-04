package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualStripeOverlapTest {
    private fun stripe(day: Int, start: Int, end: Int) = ContextualStripe(day, start, end, 0)

    @Test
    fun nestedStripeCoversTheOuterOne() {
        // builder order: outer (longer) first, nested drawn on top
        val result = ContextualStripeOverlap.coveredBy(listOf(stripe(0, 0, 1440), stripe(0, 360, 840)))
        assertEquals(listOf(listOf(1), emptyList<Int>()), result)
    }

    @Test
    fun onlyLaterStripesCount() {
        // the top stripe is never clipped, or nothing would tint the overlap
        val result = ContextualStripeOverlap.coveredBy(listOf(stripe(0, 360, 840), stripe(0, 0, 1440)))
        assertEquals(listOf(listOf(1), emptyList<Int>()), result)
    }

    @Test
    fun otherColumnsNeverOverlap() {
        val result = ContextualStripeOverlap.coveredBy(listOf(stripe(0, 0, 1440), stripe(1, 0, 1440)))
        assertEquals(listOf(emptyList<Int>(), emptyList<Int>()), result)
    }

    @Test
    fun touchingStripesDoNotOverlap() {
        val result = ContextualStripeOverlap.coveredBy(listOf(stripe(0, 360, 840), stripe(0, 840, 1320)))
        assertEquals(listOf(emptyList<Int>(), emptyList<Int>()), result)
    }

    @Test
    fun partialOverlapCounts() {
        val result = ContextualStripeOverlap.coveredBy(listOf(stripe(0, 0, 1020), stripe(0, 960, 1080)))
        assertEquals(listOf(listOf(1), emptyList<Int>()), result)
    }

    @Test
    fun threeDeepStackClipsEachLayerByEverythingAbove() {
        val result = ContextualStripeOverlap.coveredBy(listOf(stripe(0, 0, 1440), stripe(0, 360, 840), stripe(0, 600, 660)))
        assertEquals(listOf(listOf(1, 2), listOf(2), emptyList<Int>()), result)
    }
}
