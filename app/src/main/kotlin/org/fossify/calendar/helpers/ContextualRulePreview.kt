package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.joda.time.DateTimeZone

/**
 * What the rule editor shows while you type: how many upcoming events a rule would mark, and a
 * few of them. A wrong pattern otherwise fails silently (docs/CONTEXTUAL_EVENTS.md §1.5).
 * Pure, so it's unit tested. The dialog runs it through a [BoundedRunner], because a
 * pathological regex can make it arbitrarily slow.
 */
object ContextualRulePreview {
    const val PREVIEW_DAYS = 30
    const val MAX_SAMPLES = 5

    // generous for a few hundred short titles, short enough not to feel stuck while typing
    const val TIME_BUDGET_MILLIS = 300L

    data class Result(val matchCount: Int, val total: Int, val samples: List<Event>)

    fun compute(rule: ContextualRule, events: List<Event>, zone: DateTimeZone = DateTimeZone.getDefault()): Result {
        // the preview is about this rule alone, so ignore whether it's currently switched off
        val evaluator = ContextualRuleEvaluator(listOf(rule.copy(enabled = true)), zone)
        val matches = events.filter { evaluator.isContextual(it) }
        return Result(matches.size, events.size, matches.sortedBy { it.startTS }.take(MAX_SAMPLES))
    }
}
