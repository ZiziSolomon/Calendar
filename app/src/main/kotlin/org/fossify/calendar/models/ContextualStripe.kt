package org.fossify.calendar.models

/**
 * One contextual event's slice of a single week-view day column, drawn as a background stripe.
 * Minutes are 0..1440 from the start of that day.
 */
data class ContextualStripe(
    val dayIndex: Int,
    val startMinute: Int,
    val endMinute: Int,
    val color: Int,
    val title: String = "",
    // what tapping the stripe's label opens: the event, at this occurrence
    val eventId: Long = 0L,
    val occurrenceTS: Long = 0L,
    val isTask: Boolean = false,
) {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Kept free of Android types so it can be unit tested on the JVM.
     * [rowHeight] is the height of one hour, as in WeeklyViewGrid.
     */
    fun bounds(viewWidth: Int, daysCount: Int, rowHeight: Float, inset: Float, isRtl: Boolean): Bounds {
        val columnWidth = viewWidth / daysCount.toFloat()
        // the day columns live in a horizontal LinearLayout, which lays out right-to-left in RTL
        val column = if (isRtl) daysCount - 1 - dayIndex else dayIndex
        val left = column * columnWidth + inset
        val right = (column + 1) * columnWidth - inset
        return Bounds(left, minuteToY(startMinute, rowHeight), right, minuteToY(endMinute, rowHeight))
    }

    // WeeklyViewGrid draws hour line i at rowHeight * i - i / 2 (integer division), and event
    // markers apply the same correction, so stripes must too or they drift off the hour lines
    private fun minuteToY(minute: Int, rowHeight: Float): Float {
        val hour = minute / 60
        return minute * rowHeight / 60 - hour / 2
    }
}
