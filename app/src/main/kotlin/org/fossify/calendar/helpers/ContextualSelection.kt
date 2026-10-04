package org.fossify.calendar.helpers

/**
 * Which contextual action a list's selection menu offers. Pure, so it's unit tested.
 */
object ContextualSelection {
    enum class Action { MARK, UNMARK }

    /**
     * Unmark only when every selected event is directly marked: a mixed selection offers Mark,
     * which marks the rest and leaves the marked ones as they are. Events that are contextual
     * only through a title or calendar rule count as unmarked, since unmarking can't change them.
     */
    fun action(selectedIds: Collection<Long>, markedIds: Set<Long>): Action {
        return if (selectedIds.isNotEmpty() && selectedIds.all { it in markedIds }) Action.UNMARK else Action.MARK
    }

    /** Of [eventIds], those whose series has an enabled direct mark. */
    fun markedAmong(eventIds: Collection<Long>, markedSeriesIds: Set<Long>, seriesIdOf: (Long) -> Long): Set<Long> {
        if (markedSeriesIds.isEmpty()) {
            return emptySet()
        }
        return eventIds.filter { seriesIdOf(it) in markedSeriesIds }.toSet()
    }
}
