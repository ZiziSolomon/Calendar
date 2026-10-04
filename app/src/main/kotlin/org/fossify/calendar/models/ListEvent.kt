package org.fossify.calendar.models

data class ListEvent(
    var id: Long,
    var startTS: Long,
    var endTS: Long,
    var title: String,
    var description: String,
    var isAllDay: Boolean,
    var color: Int,
    var location: String,
    var isPastEvent: Boolean,
    var isRepeatable: Boolean,
    var isTask: Boolean,
    var isTaskCompleted: Boolean,
    var isAttendeeInviteDeclined: Boolean,
    var isEventCanceled: Boolean,
    var isContextual: Boolean = false
) : ListItem() {

    /**
     * Lists show contexts and commitments side by side, so a contextual row says so in words.
     * Colour and alpha are taken: upstream uses them for calendars, past events and done tasks.
     */
    fun tagTime(time: String, contextTag: String) = if (isContextual) "$time · $contextTag" else time

    companion object {
        fun from(event: Event) = ListEvent(
            id = event.id!!,
            startTS = event.startTS,
            endTS = event.endTS,
            title = event.title,
            description = event.description,
            isAllDay = event.getIsAllDay(),
            color = event.color,
            location = event.location,
            isPastEvent = event.isPastEvent,
            isRepeatable = event.repeatInterval > 0,
            isTask = event.isTask(),
            isTaskCompleted = event.isTaskCompleted(),
            isAttendeeInviteDeclined = event.isAttendeeInviteDeclined(),
            isEventCanceled = event.isEventCanceled(),
            // set by EventsHelper.getEventsSync(), which every list path goes through
            isContextual = event.isContextual
        )

        val empty = ListEvent(
            id = 0,
            startTS = 0,
            endTS = 0,
            title = "",
            description = "",
            isAllDay = false,
            color = 0,
            location = "",
            isPastEvent = false,
            isRepeatable = false,
            isTask = false,
            isTaskCompleted = false,
            isAttendeeInviteDeclined = false,
            isEventCanceled = false
        )
    }
}
