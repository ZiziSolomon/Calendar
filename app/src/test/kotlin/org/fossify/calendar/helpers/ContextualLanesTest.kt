package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.ContextualLanes.Interval
import org.fossify.calendar.helpers.ContextualLanes.Placement
import org.fossify.calendar.models.ContextualStripe
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualLanesTest {
    private fun assign(vararg intervals: Interval) = ContextualLanes.assign(intervals.toList())

    @Test
    fun loneContextKeepsTheFullWidth() {
        assertEquals(listOf(Placement(0, 1)), assign(Interval(0, 540, 1020)))
    }

    @Test
    fun concurrentContextsGetTwoLanes() {
        assertEquals(listOf(Placement(0, 2), Placement(1, 2)), assign(Interval(0, 540, 1020), Interval(0, 600, 700)))
    }

    @Test
    fun nestedContextSitsBesideTheOuterOne() {
        // "Early shift" inside an all-day "On call": both stay visible, the outer one on the left
        assertEquals(listOf(Placement(1, 2), Placement(0, 2)), assign(Interval(0, 360, 840), Interval(0, 0, 1440)))
    }

    @Test
    fun touchingContextsDoNotShareTheWidth() {
        assertEquals(listOf(Placement(0, 1), Placement(0, 1)), assign(Interval(0, 360, 840), Interval(0, 840, 1320)))
    }

    @Test
    fun otherGroupsNeverCompete() {
        assertEquals(listOf(Placement(0, 1), Placement(0, 1)), assign(Interval(0, 0, 1440), Interval(1, 0, 1440)))
    }

    @Test
    fun aFreedLaneIsReused() {
        // A all day; B morning; C afternoon: B and C share lane 1, so two lanes, not three
        val result = assign(Interval(0, 0, 1440), Interval(0, 360, 720), Interval(0, 780, 1080))
        assertEquals(listOf(Placement(0, 2), Placement(1, 2), Placement(1, 2)), result)
    }

    @Test
    fun threeAtOnceGetThreeLanes() {
        val result = assign(Interval(0, 0, 600), Interval(0, 100, 500), Interval(0, 200, 400))
        assertEquals(listOf(0, 1, 2), result.map { it.lane })
        assertEquals(setOf(3), result.map { it.laneCount }.toSet())
    }

    @Test
    fun separateClustersInOneColumnCountTheirOwnLanes() {
        // a morning pair doesn't narrow an evening context that overlaps neither
        val result = assign(Interval(0, 360, 600), Interval(0, 400, 500), Interval(0, 1080, 1320))
        assertEquals(listOf(Placement(0, 2), Placement(1, 2), Placement(0, 1)), result)
    }

    @Test
    fun chainedOverlapsShareOneCluster() {
        // A overlaps B, B overlaps C, A and C don't: still one cluster, and C reuses A's lane
        val result = assign(Interval(0, 0, 300), Interval(0, 200, 500), Interval(0, 400, 700))
        assertEquals(listOf(Placement(0, 2), Placement(1, 2), Placement(0, 2)), result)
    }

    @Test
    fun noIntervalsNoPlacements() {
        assertEquals(emptyList<Placement>(), assign())
    }

    @Test
    fun forColumnsCopiesTheLanesOntoTheStripes() {
        val stripes = listOf(ContextualStripe(0, 0, 1440, 1), ContextualStripe(0, 360, 840, 2), ContextualStripe(1, 0, 1440, 1))
        val result = ContextualLanes.forColumns(stripes)
        assertEquals(listOf(0 to 2, 1 to 2, 0 to 1), result.map { it.lane to it.laneCount })
        assertEquals(stripes.map { it.color }, result.map { it.color })
    }
}
