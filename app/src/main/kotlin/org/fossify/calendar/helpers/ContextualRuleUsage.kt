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

    /**
     * Counts per rule, in the order of [rules]. Each rule is judged on its own, switched on or
     * not, because "does this rule do anything?" is the question even when it's turned off.
     * A repeating event counts once, and its edited occurrences count as part of the series,
     * so a weekly "Gym" rule reads as 1 event, not 52.
     */
    fun count(rules: List<ContextualRule>, events: List<Event>, zone: DateTimeZone = DateTimeZone.getDefault()): List<Int> {
        return rules.map { rule ->
            val evaluator = ContextualRuleEvaluator(listOf(rule.copy(enabled = true)), zone)
            events.filter { evaluator.isContextual(it) }
                .map { if (it.parentId != 0L) it.parentId else it.id }
                .distinct()
                .size
        }
    }
}
