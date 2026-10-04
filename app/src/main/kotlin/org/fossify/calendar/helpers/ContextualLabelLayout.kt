package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe

/**
 * Where to put each week-view stripe's title. Pure (no Android types) so it's unit tested.
 *
 * Labels are sticky: a stripe whose top is scrolled out of view gets its label at the top of
 * the visible area instead, because most contexts are all-day and start at 00:00, which is
 * usually off screen. Labels that would overlap in one column are stacked, outer context
 * first, so a context nested inside another (a shift inside an on-call week) stays visible.
 */
object ContextualLabelLayout {
    class Item(val column: Int, val bounds: ContextualStripe.Bounds)

    /**
     * Which drawn label a tap at (x, y) hits, as an index into [labels], or null.
     * A label line (~14sp) is too short a finger target, so each label is hit across its
     * stripe's width and at least [minHeight] tall, centred on the label. Stacked labels make
     * those padded areas overlap; the label whose line is nearest the tap wins.
     */
    fun hitTest(labels: List<ContextualStripe.Bounds?>, x: Float, y: Float, minHeight: Float): Int? {
        return labels.indices
            .filter { i ->
                val label = labels[i] ?: return@filter false
                val slop = maxOf(0f, (minHeight - (label.bottom - label.top)) / 2)
                x >= label.left && x < label.right && y >= label.top - slop && y < label.bottom + slop
            }
            .minByOrNull { i ->
                val label = labels[i]!!
                kotlin.math.abs(y - (label.top + label.bottom) / 2)
            }
    }

    /**
     * Returns the top y of each item's label, in input order, or null where it doesn't fit
     * (stripe not visible, or no room left inside it after stacking).
     */
    fun place(items: List<Item>, visibleTop: Float, visibleBottom: Float, lineHeight: Float, padding: Float): List<Float?> {
        val result = arrayOfNulls<Float>(items.size)
        items.indices.groupBy { items[it].column }.values.forEach { indexes ->
            val ordered = indexes.sortedWith(
                // earlier effective top first; at the same spot the longer (outer) stripe wins
                compareBy<Int> { maxOf(items[it].bounds.top, visibleTop) }
                    .thenByDescending { items[it].bounds.bottom - items[it].bounds.top }
            )

            var nextFree = Float.NEGATIVE_INFINITY
            for (i in ordered) {
                val b = items[i].bounds
                if (b.bottom <= visibleTop || b.top >= visibleBottom) {
                    continue
                }

                val y = maxOf(b.top + padding, visibleTop + padding, nextFree)
                val fits = y + lineHeight <= minOf(b.bottom, visibleBottom)
                if (fits) {
                    result[i] = y
                    nextFree = y + lineHeight
                }
            }
        }
        return result.toList()
    }
}
