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

    // hit-testing: labels are 30px lines here; a 48px minimum target pads each by 9px above and below
    private val minTouch = 48f
    private fun label(left: Float, top: Float) = Bounds(left, top, left + 100f, top + line)

    @Test
    fun tapOnALabelHitsIt() {
        assertEquals(0, ContextualLabelLayout.hitTest(listOf(label(0f, 500f)), 50f, 515f, minTouch))
    }

    @Test
    fun targetIsPaddedToAFingerButNoFurther() {
        val labels = listOf(label(0f, 500f))
        assertEquals(0, ContextualLabelLayout.hitTest(labels, 50f, 492f, minTouch))
        assertEquals(0, ContextualLabelLayout.hitTest(labels, 50f, 538f, minTouch))
        // below the padded label is plain stripe: the grid's tap-to-create applies there
        assertNull(ContextualLabelLayout.hitTest(labels, 50f, 540f, minTouch))
        assertNull(ContextualLabelLayout.hitTest(labels, 50f, 490f, minTouch))
    }

    @Test
    fun otherColumnsAndUndrawnLabelsAreNotHit() {
        val labels = listOf(null, label(100f, 500f))
        assertNull(ContextualLabelLayout.hitTest(labels, 50f, 515f, minTouch))
        assertEquals(1, ContextualLabelLayout.hitTest(labels, 150f, 515f, minTouch))
    }

    @Test
    fun stackedLabelsGoToTheNearestLine() {
        // nested context: second label stacked straight under the first, padded areas overlap
        val labels = listOf(label(0f, 500f), label(0f, 530f))
        assertEquals(0, ContextualLabelLayout.hitTest(labels, 50f, 520f, minTouch))
        assertEquals(1, ContextualLabelLayout.hitTest(labels, 50f, 535f, minTouch))
    }
}
