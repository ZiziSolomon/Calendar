package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.ContextualLabelLayout.Item
import org.fossify.calendar.models.ContextualStripe.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualLabelLayoutTest {
    private val line = 30f
    private val pad = 4f

    private fun item(column: Int, top: Float, bottom: Float) = Item(column, Bounds(0f, top, 100f, bottom))

    private fun place(vararg items: Item, visibleTop: Float = 0f, visibleBottom: Float = 2000f) =
        ContextualLabelLayout.place(items.toList(), visibleTop, visibleBottom, line, pad)

    @Test
    fun labelSitsAtTheTopOfAVisibleStripe() {
        assertEquals(listOf(504f), place(item(0, 500f, 900f)))
    }

    @Test
    fun labelSticksToTheVisibleTopWhenTheStripeStartsAboveIt() {
        // an all-day stripe starting at 00:00 while the user is scrolled to 08:00
        assertEquals(listOf(804f), place(item(0, 0f, 1440f), visibleTop = 800f))
    }

    @Test
    fun nestedContextStacksBelowTheOuterOne() {
        // On call fills the day; Early shift starts above the visible area too
        val result = place(item(0, 0f, 1440f), item(0, 360f, 840f), visibleTop = 400f)
        assertEquals(listOf(404f, 434f), result)
    }

    @Test
    fun outerContextIsLabelledFirstWhateverTheInputOrder() {
        val result = place(item(0, 360f, 840f), item(0, 0f, 1440f), visibleTop = 400f)
        assertEquals(listOf(434f, 404f), result)
    }

    @Test
    fun nestedStripeBelowTheOuterLabelKeepsItsOwnTop() {
        val result = place(item(0, 0f, 1440f), item(0, 600f, 900f), visibleTop = 400f)
        assertEquals(listOf(404f, 604f), result)
    }

    @Test
    fun columnsDontPushEachOtherDown() {
        val result = place(item(0, 0f, 1440f), item(1, 0f, 1440f), visibleTop = 400f)
        assertEquals(listOf(404f, 404f), result)
    }

    @Test
    fun stripesOutsideTheVisibleRangeGetNoLabel() {
        val result = place(item(0, 0f, 300f), item(0, 1500f, 1600f), visibleTop = 400f, visibleBottom = 1400f)
        assertEquals(listOf(null, null), result)
    }

    @Test
    fun aStripeTooShortForItsLabelGetsNone() {
        assertNull(place(item(0, 500f, 520f)).single())
    }

    @Test
    fun aLabelThatWouldBePushedOutOfItsStripeIsDropped() {
        // the nested stripe is only one line tall and the outer label already took that line
        val result = place(item(0, 0f, 1440f), item(0, 400f, 440f), visibleTop = 400f)
        assertEquals(listOf(404f, null), result)
    }

    @Test
    fun labelNeverRunsPastTheBottomOfTheScreen() {
        assertNull(place(item(0, 1390f, 1600f), visibleBottom = 1400f).single())
    }
}
