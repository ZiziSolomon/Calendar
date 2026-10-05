package org.fossify.calendar.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualStripeTest {
    private val width = 700           // 7 columns of 100px
    private val rowHeight = 60f       // 1px per minute, keeps the arithmetic readable

    private fun bounds(stripe: ContextualStripe, isRtl: Boolean = false, inset: Float = 0f) =
        stripe.bounds(width, 7, rowHeight, inset, isRtl)

    @Test
    fun stripeOccupiesItsDayColumn() {
        val b = bounds(ContextualStripe(dayIndex = 2, startMinute = 0, endMinute = 60, color = 0))
        assertEquals(200f, b.left)
        assertEquals(300f, b.right)
    }

    @Test
    fun insetShrinksBothSides() {
        val b = bounds(ContextualStripe(dayIndex = 0, startMinute = 0, endMinute = 60, color = 0), inset = 2f)
        assertEquals(2f, b.left)
        assertEquals(98f, b.right)
    }

    @Test
    fun rtlMirrorsTheColumn() {
        // LinearLayout puts the first day on the right in RTL
        val b = bounds(ContextualStripe(dayIndex = 0, startMinute = 0, endMinute = 60, color = 0), isRtl = true)
        assertEquals(600f, b.left)
        assertEquals(700f, b.right)
    }

    @Test
    fun lanesSplitTheColumn() {
        val first = bounds(ContextualStripe(dayIndex = 1, startMinute = 0, endMinute = 60, color = 0, lane = 0, laneCount = 2))
        val second = bounds(ContextualStripe(dayIndex = 1, startMinute = 0, endMinute = 60, color = 0, lane = 1, laneCount = 2))
        assertEquals(100f, first.left)
        assertEquals(150f, first.right)
        assertEquals(150f, second.left)
        assertEquals(200f, second.right)
    }

    @Test
    fun lanesKeepAnInsetGapBetweenThem() {
        // 100px column, 2px inset each side and 2px between: two 47px lanes
        val first = bounds(ContextualStripe(dayIndex = 0, startMinute = 0, endMinute = 60, color = 0, lane = 0, laneCount = 2), inset = 2f)
        val second = bounds(ContextualStripe(dayIndex = 0, startMinute = 0, endMinute = 60, color = 0, lane = 1, laneCount = 2), inset = 2f)
        assertEquals(2f, first.left)
        assertEquals(49f, first.right)
        assertEquals(51f, second.left)
        assertEquals(98f, second.right)
    }

    @Test
    fun rtlMirrorsTheLanesToo() {
        val first = bounds(ContextualStripe(dayIndex = 0, startMinute = 0, endMinute = 60, color = 0, lane = 0, laneCount = 2), isRtl = true)
        assertEquals(650f, first.left)
        assertEquals(700f, first.right)
    }

    @Test
    fun verticalPositionMatchesTheGridLineCorrection() {
        // WeeklyViewGrid draws hour line i at rowHeight * i - i / 2
        val b = bounds(ContextualStripe(dayIndex = 0, startMinute = 9 * 60, endMinute = 17 * 60, color = 0))
        assertEquals(540f - 4, b.top)
        assertEquals(1020f - 8, b.bottom)
    }

    @Test
    fun fullDayStripeReachesTheBottomOfTheGrid() {
        val b = bounds(ContextualStripe(dayIndex = 0, startMinute = 0, endMinute = 1440, color = 0))
        assertEquals(0f, b.top)
        assertEquals(1440f - 12, b.bottom)
    }
}
