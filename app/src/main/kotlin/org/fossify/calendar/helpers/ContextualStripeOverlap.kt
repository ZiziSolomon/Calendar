package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe

/**
 * Which stripes are painted over which. The week view draws each stripe translucent with the
 * stripes painted after it clipped out, so an overlap is tinted once (by the top stripe) instead
 * of darkening. That replaces a full-height saveLayer, which cost an offscreen buffer on every
 * frame of a swipe. Pure so the overlap rules can be unit tested on the JVM.
 */
object ContextualStripeOverlap {
    /** For each stripe, the indices of later stripes in the same column that overlap it. */
    fun coveredBy(stripes: List<ContextualStripe>): List<List<Int>> {
        return stripes.mapIndexed { i, stripe ->
            (i + 1 until stripes.size).filter { j ->
                val other = stripes[j]
                // touching end-to-start (a shift ending as the next starts) is not an overlap
                other.dayIndex == stripe.dayIndex && other.startMinute < stripe.endMinute && stripe.startMinute < other.endMinute
            }
        }
    }
}
