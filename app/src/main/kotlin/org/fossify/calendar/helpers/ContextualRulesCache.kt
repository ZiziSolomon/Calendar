package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.extensions.contextualRulesDB

/**
 * Holds the app-wide evaluator so rules are read and regexes compiled once, not on every
 * fetch (week view refetches on every page swipe). Anything that changes rules must call
 * [invalidate].
 */
object ContextualRulesCache {
    @Volatile
    private var evaluator: ContextualRuleEvaluator? = null

    /** Hits the database on first use, so call it from a background thread. */
    fun getEvaluator(context: Context): ContextualRuleEvaluator {
        return evaluator ?: ContextualRuleEvaluator(context.contextualRulesDB.getEnabledRules()).also {
            evaluator = it
        }
    }

    fun invalidate() {
        evaluator = null
    }
}
