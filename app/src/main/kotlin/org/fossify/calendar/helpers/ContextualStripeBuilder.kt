package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Days
import org.joda.time.LocalDate

/**
 * Slices contextual events into one stripe per visible day column. Pure (Joda only) so the
 * edge cases can be unit tested without a device.
 *
 * Unlike real events, contextual ones always become stripes: all-day and midnight-spanning
 * ones don't move to the top bar, because a background context is exactly what a full-height
 * stripe is for.
 */
object ContextualStripeBuilder {
    private const val MINUTES_PER_DAY = 1440

    // a zero-length contextual event would otherwise be invisible; matches roughly the
    // minimal height real event markers get
    internal const val MIN_STRIPE_MINUTES = 15

    fun build(
        events: List<Event>,
        firstDay: LocalDate,
        daysCount: Int,
        zone: DateTimeZone = DateTimeZone.getDefault(),
        fallbackColor: Int = 0
    ): List<ContextualStripe> {
        val lastDay = firstDay.plusDays(daysCount - 1)
        val stripes = ArrayList<ContextualStripe>()
        // longest events first, so ties in the stable sort below also favour the nested one
        for (event in events.sortedByDescending { it.endTS - it.startTS }) {
            val color = if (event.color == 0) fallbackColor else event.color
            val start = DateTime(event.startTS * 1000L, zone)
            val end = DateTime(event.endTS * 1000L, zone)
            val startDay = start.toLocalDate()
            val endDay = end.toLocalDate()

            // only walk the visible days: a contextual event can span months ("school term")
            var day = maxOf(startDay, firstDay)
            val until = minOf(endDay, lastDay)
            while (!day.isAfter(until)) {
                val dayIndex = Days.daysBetween(firstDay, day).days
                slice(event, start, end, day, startDay, endDay)?.let { (startMinute, endMinute) ->
                    stripes.add(ContextualStripe(dayIndex, startMinute, endMinute, color))
                }
                day = day.plusDays(1)
            }
        }

        // drawn in this order: longest first, so a shorter context nested inside a longer one
        // (a weekend inside an on-call week) is painted last and stays visible on top
        return stripes.sortedByDescending { it.endMinute - it.startMinute }
    }

    private fun slice(event: Event, start: DateTime, end: DateTime, day: LocalDate, startDay: LocalDate, endDay: LocalDate): Pair<Int, Int>? {
        if (event.getIsAllDay()) {
            return Pair(0, MINUTES_PER_DAY)
        }

        val startMinute = if (day == startDay) start.minuteOfDay else 0
        val endMinute = if (day == endDay) end.minuteOfDay else MINUTES_PER_DAY

        // an event ending exactly at midnight shouldn't leave a sliver on the next day
        if (day != startDay && endMinute == 0) {
            return null
        }

        return if (endMinute - startMinute < MIN_STRIPE_MINUTES) {
            val paddedStart = startMinute.coerceAtMost(MINUTES_PER_DAY - MIN_STRIPE_MINUTES)
            Pair(paddedStart, paddedStart + MIN_STRIPE_MINUTES)
        } else {
            Pair(startMinute, endMinute)
        }
    }
}
