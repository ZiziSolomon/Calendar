package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualStripe

/**
 * The key under the week and month views: one entry per distinct context on screen. Contexts
 * are routine ("On call" every week), so Zizi wanted the stripes themselves untitled and the
 * names collected once in a key (Phase 16). Pure so the grouping can be unit tested.
 */
object ContextualKey {
    data class Entry(val title: String, val color: Int, val eventId: Long, val occurrenceTS: Long, val isTask: Boolean)

    /**
     * Ordered by first appearance (earliest day, then earliest time). Slices of the same context
     * on several days, and separate events sharing a title and colour, collapse into one entry,
     * which opens the first occurrence on screen.
     */
    fun entries(stripes: List<ContextualStripe>): List<Entry> {
        return stripes
            .sortedWith(compareBy({ it.dayIndex }, { it.startMinute }, { it.lane }))
            .distinctBy { it.title.trim().lowercase() to it.color }
            .map { Entry(it.title.trim(), it.color, it.eventId, it.occurrenceTS, it.isTask) }
    }
}
