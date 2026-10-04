package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.R
import org.fossify.calendar.extensions.contextualRulesDB
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.models.ContextualRule
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * All rule writes go through here so the cached evaluator is always invalidated; a write that
 * bypassed this would leave every view showing stale contextual-ness until the app restarted.
 */
class ContextualRulesHelper(val context: Context) {
    private val dao = context.contextualRulesDB

    fun getRules(callback: (ArrayList<ContextualRule>) -> Unit) {
        ensureBackgroundThread {
            callback(ArrayList(dao.getRules()))
        }
    }

    fun saveRule(rule: ContextualRule, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            rule.id = dao.insertOrUpdate(rule)
            rulesChanged()
            callback?.invoke()
        }
    }

    fun deleteRules(rules: List<ContextualRule>, callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            dao.deleteRules(rules)
            rulesChanged()
            callback?.invoke()
        }
    }

    private fun rulesChanged() {
        ContextualRulesCache.invalidate()
        context.updateWidgets()
    }

    /** One-line summary for the rules list. Call from a background thread (may read an event). */
    fun describe(rule: ContextualRule): String {
        val res = context.resources
        return when (rule.matchType) {
            MATCH_ALL -> res.getString(R.string.contextual_rule_all_events)
            MATCH_TITLE_CONTAINS -> res.getString(R.string.contextual_rule_title_contains, rule.pattern)
            MATCH_TITLE_REGEX -> res.getString(R.string.contextual_rule_title_regex, rule.pattern)
            MATCH_DURATION_OVER -> res.getString(R.string.contextual_rule_duration_over, formatDuration(rule.pattern))
            MATCH_EVENT_ID -> {
                val title = rule.eventId?.let { context.eventsDB.getEventWithId(it)?.title }
                if (title == null) {
                    res.getString(R.string.contextual_rule_event_missing)
                } else {
                    res.getString(R.string.contextual_rule_event, title)
                }
            }

            else -> ""
        }
    }

    private fun formatDuration(pattern: String): String {
        val minutes = pattern.trim().toLongOrNull() ?: return pattern
        val (amount, unit) = ContextualRuleText.durationParts(minutes)
        val pluralsId = when (unit) {
            ContextualRuleText.Unit.DAYS -> org.fossify.commons.R.plurals.days
            ContextualRuleText.Unit.HOURS -> org.fossify.commons.R.plurals.hours
            ContextualRuleText.Unit.MINUTES -> org.fossify.commons.R.plurals.minutes
        }
        return context.resources.getQuantityString(pluralsId, amount.toInt(), amount.toInt())
    }
}
