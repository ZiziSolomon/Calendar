package org.fossify.calendar.helpers

import org.fossify.calendar.models.Event

/**
 * Whether a due reminder should be shown. Contexts are background ("On call", "Kids weekend"),
 * so with Settings → "Mute reminders for contextual events" on, their reminders are dropped.
 *
 * Applied when a reminder *fires*, never when it's scheduled: the alarm chain keeps running for
 * muted events, so turning the setting off takes effect from the very next reminder.
 */
object ContextualReminderPolicy {
    /** [evaluator] is only built when muting is on, so the common case costs no DB read. */
    fun shouldNotify(event: Event, muteContextual: Boolean, evaluator: () -> ContextualRuleEvaluator): Boolean {
        return !muteContextual || !evaluator().isContextual(event)
    }
}
