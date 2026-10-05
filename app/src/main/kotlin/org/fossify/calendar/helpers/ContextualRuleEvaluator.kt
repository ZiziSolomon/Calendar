package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.joda.time.DateTimeZone
import org.joda.time.Days
import org.joda.time.LocalDate
import java.util.regex.PatternSyntaxException

/**
 * The single place that decides whether an event is contextual. Week view, day view and the
 * widget all go through this, so they can't drift apart. See docs/CONTEXTUAL_EVENTS.md §1.
 *
 * Immutable: patterns are compiled once in the constructor, so the instance *is* the per-rule
 * cache. When rules change, build a new evaluator rather than mutating this one.
 *
 * Call it when events are loaded, not while drawing: Java regex has no timeout, and a
 * catastrophically backtracking pattern must not be able to freeze the UI thread (§1.4).
 */
class ContextualRuleEvaluator(
    rules: List<ContextualRule>,
    private val zone: DateTimeZone = DateTimeZone.getDefault()
) {
    private val enabledRules = rules.filter { it.enabled }

    // keyed by rule identity, so unsaved rules (id == null) from the editor's preview work too
    private val compiledRegexes: Map<ContextualRule, Regex?> = enabledRules
        .filter { it.matchType == MATCH_TITLE_REGEX }
        .associateWith { compileOrNull(it.pattern) }

    val isEmpty = enabledRules.isEmpty()

    // most installs set no key names or colours, so the per-event display lookup can be skipped
    private val hasDisplayOverrides = enabledRules.any { !it.keyName.isNullOrBlank() || it.keyColor != null }

    fun isContextual(event: Event) = !isEmpty && enabledRules.any { matches(it, event) }

    /** Every enabled rule that makes [event] contextual, in rule order. For explaining, not for hot paths. */
    fun matchingRules(event: Event): List<ContextualRule> = enabledRules.filter { matches(it, event) }

    /** The key name and colour [event] shows with, from the most specific matching rule (ContextualDisplay). */
    fun display(event: Event): ContextualDisplay.Result =
        if (hasDisplayOverrides) ContextualDisplay.resolve(matchingRules(event)) else ContextualDisplay.NONE

    private fun matches(rule: ContextualRule, event: Event): Boolean {
        if (rule.calendarId != null && rule.calendarId != event.calendarId) {
            return false
        }

        return when (rule.matchType) {
            MATCH_ALL -> true
            // a blank pattern would match everything; a half-typed rule shouldn't do that
            MATCH_TITLE_CONTAINS -> rule.pattern.isNotBlank() && event.title.contains(rule.pattern, ignoreCase = true)
            MATCH_TITLE_REGEX -> compiledRegexes[rule]?.containsMatchIn(event.title) == true
            MATCH_DURATION_OVER -> {
                val thresholdMinutes = rule.pattern.trim().toLongOrNull() ?: return false
                thresholdMinutes >= 0 && durationMinutes(event) > thresholdMinutes
            }
            MATCH_EVENT_ID -> matchesEvent(rule, event)
            else -> false
        }
    }

    private fun matchesEvent(rule: ContextualRule, event: Event): Boolean {
        val ruleEventId = rule.eventId
        if (ruleEventId != null) {
            // whole-series marking (§4 decision 3): edited occurrences of a repeating event are
            // stored as separate child events whose parentId points at the series
            if (event.id == ruleEventId || (event.parentId != 0L && event.parentId == ruleEventId)) {
                return true
            }
        }

        // repair key: survives a wipe-and-resync that reassigns local ids
        val ruleImportId = rule.importId
        return !ruleImportId.isNullOrEmpty() && event.importId == ruleImportId
    }

    /**
     * All-day events are stored as local midnight to *noon* on their last day (see
     * EventActivity and Event.toLocalAllDayEvent()), so their raw length is a meaningless
     * 12h, 36h, 60h... Count them as whole days instead: a one-day all-day event is exactly
     * 24h and doesn't pass a "more than a day" rule, while a Fri–Sun one is 72h and does.
     * This is §1.2's "spans more than one day code", generalised to any threshold.
     */
    internal fun durationMinutes(event: Event): Long {
        return if (event.getIsAllDay()) {
            val firstDay = LocalDate(event.startTS * 1000L, zone)
            val lastDay = LocalDate(event.endTS * 1000L, zone)
            (Days.daysBetween(firstDay, lastDay).days + 1) * MINUTES_PER_DAY
        } else {
            (event.endTS - event.startTS) / 60L
        }
    }

    companion object {
        private const val MINUTES_PER_DAY = 24 * 60L

        /** Returns the regex error message to show the user, or null if the pattern is valid. */
        fun regexError(pattern: String): String? = try {
            Regex(pattern)
            null
        } catch (e: PatternSyntaxException) {
            e.description
        }

        private fun compileOrNull(pattern: String): Regex? {
            // risky patterns never run here, even if saved before RegexRisk existed: this is the
            // event fetch path, and a runaway match can't be interrupted on Android
            if (pattern.isBlank() || RegexRisk.check(pattern) != null) {
                return null
            }

            // a malformed pattern disables just its own rule, never crashes the caller
            return try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (e: PatternSyntaxException) {
                null
            }
        }
    }
}
