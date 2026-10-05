package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe

/**
 * Month view contexts (Zizi, Phase 16): each week row is one time axis running left to right,
 * from its first day's 00:00 to its last day's 24:00, so a context is a thin bar at its true
 * times. A multi-day context is one continuous bar through the row instead of a piece per
 * cell. Pure so the geometry can be unit tested on the JVM.
 */
object ContextualMonthBars {
    const val MINUTES_PER_DAY = 1440

    data class Bar(
        val row: Int,
        // minutes from the row's first day at 00:00, so 0..daysPerRow * 1440
        val start: Int,
        val end: Int,
        val lane: Int,
        val color: Int,
        val title: String,
        val eventId: Long,
        val occurrenceTS: Long,
        val isTask: Boolean,
    ) {
        /** Left and right edges in a row of [daysPerRow] cells of [dayWidth], after [offset]. */
        fun xRange(dayWidth: Float, offset: Float): Pair<Float, Float> {
            // multiply before dividing, so whole-day edges land exactly on the cell lines
            return Pair(offset + start * dayWidth / MINUTES_PER_DAY, offset + end * dayWidth / MINUTES_PER_DAY)
        }
    }

    /**
     * Whether [bar] is over at [nowMinute] on grid day [nowDayIndex], so it dims like a past
     * event. Off-grid "now" works too: -1 for a grid that starts after today (nothing past), or
     * past the last index for one that ended before today (everything past).
     */
    fun hasEnded(bar: Bar, nowDayIndex: Int, nowMinute: Int, daysPerRow: Int = 7): Boolean {
        val barEnd = bar.row.toLong() * daysPerRow * MINUTES_PER_DAY + bar.end
        val now = nowDayIndex.toLong() * MINUTES_PER_DAY + nowMinute
        return barEnd <= now
    }

    /**
     * [stripes] are day slices over the whole grid (ContextualStripeBuilder with the grid's
     * first day and 42 days). Slices of one occurrence on neighbouring days of a row are joined
     * back into one bar; a context running past the row's end continues as a new bar on the
     * next row.
     */
    fun layout(stripes: List<ContextualStripe>, daysPerRow: Int = 7): List<Bar> {
        val pieces = stripes.map { stripe ->
            val row = stripe.dayIndex / daysPerRow
            val column = stripe.dayIndex % daysPerRow
            val dayStart = column * MINUTES_PER_DAY
            Bar(row, dayStart + stripe.startMinute, dayStart + stripe.endMinute, 0, stripe.color, stripe.title, stripe.eventId, stripe.occurrenceTS, stripe.isTask)
        }

        val joined = pieces
            .groupBy { listOf(it.row, it.eventId, it.occurrenceTS, it.color, it.title) }
            .values
            .flatMap { group ->
                val merged = ArrayList<Bar>()
                group.sortedBy { it.start }.forEach { piece ->
                    val last = merged.lastOrNull()
                    // the slice ending at 24:00 meets the next day's slice starting at 00:00
                    if (last != null && piece.start <= last.end) {
                        merged[merged.lastIndex] = last.copy(end = maxOf(last.end, piece.end))
                    } else {
                        merged.add(piece)
                    }
                }
                merged
            }

        val placements = ContextualLanes.assign(joined.map { ContextualLanes.Interval(it.row, it.start, it.end) })
        return joined.mapIndexed { i, bar -> bar.copy(lane = placements[i].lane) }
            .sortedWith(compareBy({ it.row }, { it.lane }, { it.start }))
    }
}
