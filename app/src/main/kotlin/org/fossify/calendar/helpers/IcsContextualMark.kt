package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event

/**
 * Carries direct marks (MATCH_EVENT_ID rules) through .ics export/import as an
 * `X-FOSSIFY-CONTEXTUAL:TRUE` property, so a mark on a local event survives a phone move, which
 * the settings export can't do (its marks are keyed by ids local to this phone).
 * Other apps ignore unknown X- properties (RFC 5545 §3.8.8.2).
 *
 * Only direct marks travel: title, duration and calendar rules are already in the settings export,
 * and writing "contextual" for every rule match would freeze today's rules into the file.
 */
object IcsContextualMark {
    const val LINE = "${FOSSIFY_CONTEXTUAL}TRUE"

    fun markedEventIds(rules: List<ContextualRule>): Set<Long> =
        rules.filter { it.enabled && it.matchType == MATCH_EVENT_ID }.mapNotNull { it.eventId }.toSet()

    /** An edited occurrence is its own VEVENT in the file, and is marked if its series is. */
    fun isMarked(event: Event, markedIds: Set<Long>): Boolean =
        event.id in markedIds || (event.parentId != 0L && event.parentId in markedIds)

    /** [value] is what follows the property name; anything but TRUE is treated as unmarked. */
    fun parse(value: String): Boolean = value.trim().equals("TRUE", ignoreCase = true)
}
