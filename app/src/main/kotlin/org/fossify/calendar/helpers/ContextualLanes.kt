package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe

/**
 * Side-by-side lanes for contexts that happen at the same time, so neither hides the other.
 * Zizi's call on the 9a (Phase 16): two concurrent contexts are two narrow stripes, not one
 * painted over the other. Pure so the packing can be unit tested on the JVM.
 *
 * Intervals only compete inside their own group: a week-view day column, or a month-view
 * week row. Within a group, every interval in one overlap cluster shares that cluster's lane
 * count, the same rule upstream uses for packing real events, so a lane never changes width
 * partway down a stripe.
 */
object ContextualLanes {
    data class Interval(val group: Int, val start: Int, val end: Int)

    data class Placement(val lane: Int, val laneCount: Int)

    fun assign(intervals: List<Interval>): List<Placement> {
        val placements = arrayOfNulls<Placement>(intervals.size)
        intervals.indices.groupBy { intervals[it].group }.values.forEach { indices ->
            // earliest first; on a tie the longer one takes the lower lane, so an all-day context
            // keeps lane 0 on every day it spans instead of swapping with whatever starts at 00:00
            val sorted = indices.sortedWith(compareBy({ intervals[it].start }, { -(intervals[it].end - intervals[it].start) }, { it }))
            var cluster = ArrayList<Pair<Int, Int>>()   // (interval index, lane)
            val laneEnds = ArrayList<Int>()
            var clusterEnd = Int.MIN_VALUE

            fun closeCluster() {
                cluster.forEach { (index, lane) -> placements[index] = Placement(lane, laneEnds.size) }
                cluster = ArrayList()
                laneEnds.clear()
            }

            for (index in sorted) {
                val interval = intervals[index]
                // touching end-to-start (a shift ending as the next begins) is not an overlap
                if (cluster.isNotEmpty() && interval.start >= clusterEnd) {
                    closeCluster()
                    clusterEnd = Int.MIN_VALUE
                }

                var lane = laneEnds.indexOfFirst { it <= interval.start }
                if (lane == -1) {
                    lane = laneEnds.size
                    laneEnds.add(interval.end)
                } else {
                    laneEnds[lane] = interval.end
                }
                cluster.add(index to lane)
                clusterEnd = maxOf(clusterEnd, interval.end)
            }
            closeCluster()
        }
        return placements.map { it!! }
    }

    /** Week and day view: lanes per day column. */
    fun forColumns(stripes: List<ContextualStripe>): List<ContextualStripe> {
        val placements = assign(stripes.map { Interval(it.dayIndex, it.startMinute, it.endMinute) })
        return stripes.mapIndexed { i, stripe -> stripe.copy(lane = placements[i].lane, laneCount = placements[i].laneCount) }
    }
}
