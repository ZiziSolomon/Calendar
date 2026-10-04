package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.joda.time.DateTimeZone

/**
 * How many events each rule matches, for the rules screen. A rule with a typo in its title, or
 * scoped to a calendar that has gone, matches nothing and otherwise fails silently.
 * Pure, so it's unit tested.
 */
object ContextualRuleUsage {
    // wide enough that a rule for something seasonal (a holiday rota) still shows as alive
    const val DAYS_BACK = 31
    const val DAYS_AHEAD = 365

    // the matches dialog is for checking a rule's reach, not browsing; past this, refine the rule
    const val MAX_LISTED = 50

    /**
     * Counts per rule, in the order of [rules]. Each rule is judged on its own, switched on or
     * not, because "does this rule do anything?" is the question even when it's turned off.
     * A repeating event counts once, and its edited occurrences count as part of the series,
     * so a weekly "Gym" rule reads as 1 event, not 52.
     */
    fun count(rules: List<ContextualRule>, events: List<Event>, zone: DateTimeZone = DateTimeZone.getDefault()): List<Int> {
        return rules.map { matches(it, events, nowTS = 0L, zone = zone).size }
    }

    /**
     * The events behind [count], one per event (series), for "Show matches". Each is represented by
     * its next occurrence from [nowTS], or its latest one if it's all in the past, so tapping it
     * opens the occurrence that matters. Upcoming events come first, soonest first; then past
     * ones, most recent first.
     */
    fun matches(rule: ContextualRule, events: List<Event>, nowTS: Long, zone: DateTimeZone = DateTimeZone.getDefault()): List<Event> {
        val evaluator = ContextualRuleEvaluator(listOf(rule.copy(enabled = true)), zone)
        val representatives = events.filter { evaluator.isContextual(it) }
            .groupBy { seriesKey(it) }
            .values
            .map { occurrences -> occurrences.filter { it.startTS >= nowTS }.minByOrNull { it.startTS } ?: occurrences.maxBy { it.startTS } }

        val (upcoming, past) = representatives.partition { it.startTS >= nowTS }
        return upcoming.sortedBy { it.startTS } + past.sortedByDescending { it.startTS }
    }

    // edited occurrences are stored as child events pointing at their series
    private fun seriesKey(event: Event) = if (event.parentId != 0L) event.parentId else event.id
}
