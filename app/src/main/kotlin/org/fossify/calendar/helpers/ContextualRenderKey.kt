package org.fossify.calendar.helpers

import org.fossify.calendar.models.Event

/**
 * Week and day views skip a redraw when nothing changed, judged by a hash. Whether contexts are
 * shown changes the drawing without changing the events, so it has to be part of that hash, or
 * the main screen's quick toggle would do nothing until the next swipe.
 */
object ContextualRenderKey {
    fun of(events: List<Event>, showContextual: Boolean) = 31 * events.hashCode() + showContextual.hashCode()
}
