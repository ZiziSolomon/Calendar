package org.fossify.calendar.dialogs

import android.text.InputType
import androidx.appcompat.app.AlertDialog
import org.fossify.calendar.R
import org.fossify.calendar.activities.SimpleActivity
import org.fossify.calendar.databinding.DialogContextualRuleBinding
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.helpers.ContextualRuleEvaluator
import org.fossify.calendar.helpers.ContextualRuleText
import org.fossify.calendar.helpers.ContextualRulesHelper
import org.fossify.calendar.helpers.MATCH_ALL
import org.fossify.calendar.helpers.MATCH_DURATION_OVER
import org.fossify.calendar.helpers.MATCH_EVENT_ID
import org.fossify.calendar.helpers.MATCH_TITLE_CONTAINS
import org.fossify.calendar.helpers.MATCH_TITLE_REGEX
import org.fossify.calendar.models.CalendarEntity
import org.fossify.calendar.models.ContextualRule
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.models.RadioItem

class EditContextualRuleDialog(
    val activity: SimpleActivity,
    original: ContextualRule?,
    defaultCalendarId: Long? = null,
    val callback: () -> Unit
) {
    private val isNewRule = original == null
    // edit a copy, so cancelling leaves the list's rule untouched
    private val rule = original?.copy() ?: ContextualRule(id = null, calendarId = defaultCalendarId, matchType = MATCH_TITLE_CONTAINS)
    private val binding by activity.viewBinding(DialogContextualRuleBinding::inflate)
    private var calendars = ArrayList<CalendarEntity>()

    // remembered per type, so flipping between types while editing doesn't lose what was typed
    private val inputPerType = HashMap<Int, String>()

    init {
        inputPerType[rule.matchType] = if (rule.matchType == MATCH_DURATION_OVER) {
            ContextualRuleText.patternToHoursInput(rule.pattern)
        } else {
            rule.pattern
        }

        binding.apply {
            contextualRuleType.setOnClickListener { pickType() }
            contextualRuleCalendar.setOnClickListener { pickCalendar() }
            contextualRulePattern.onTextChangeListener {
                inputPerType[rule.matchType] = it
                showPatternError()
            }
        }
        showType()

        activity.eventsHelper.getCalendars(activity, false) {
            calendars = it
            showCalendar()
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = if (isNewRule) R.string.add_contextual_rule else R.string.edit_contextual_rule
                ) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (applyInput()) {
                            ContextualRulesHelper(activity).saveRule(rule) {
                                activity.runOnUiThread {
                                    alertDialog.dismiss()
                                    callback()
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun typeName(matchType: Int) = activity.getString(
        when (matchType) {
            MATCH_ALL -> R.string.contextual_match_all
            MATCH_TITLE_REGEX -> R.string.contextual_match_title_regex
            MATCH_DURATION_OVER -> R.string.contextual_match_duration_over
            MATCH_EVENT_ID -> R.string.contextual_match_event
            else -> R.string.contextual_match_title_contains
        }
    )

    private fun pickType() {
        // single-event rules come from the event's long-press menu, and can't be turned into one here
        if (rule.matchType == MATCH_EVENT_ID) {
            return
        }

        val items = arrayListOf(MATCH_TITLE_CONTAINS, MATCH_TITLE_REGEX, MATCH_DURATION_OVER, MATCH_ALL)
            .map { RadioItem(it, typeName(it)) }
        RadioGroupDialog(activity, ArrayList(items), rule.matchType) {
            rule.matchType = it as Int
            showType()
        }
    }

    private fun showType() {
        binding.apply {
            contextualRuleType.setText(typeName(rule.matchType))
            val needsPattern = rule.matchType != MATCH_ALL && rule.matchType != MATCH_EVENT_ID
            contextualRulePatternHint.beVisibleIf(needsPattern)
            contextualRulePatternHint.hint = activity.getString(
                when (rule.matchType) {
                    MATCH_TITLE_REGEX -> R.string.contextual_rule_regex_hint
                    MATCH_DURATION_OVER -> R.string.contextual_rule_hours_hint
                    else -> R.string.contextual_rule_text_hint
                }
            )
            contextualRulePattern.inputType = if (rule.matchType == MATCH_DURATION_OVER) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                // no autocorrect: it "fixes" regexes and shift codes into nonsense
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            contextualRulePattern.setText(inputPerType[rule.matchType] ?: "")
            contextualRulePattern.setSelection(contextualRulePattern.length())
        }
        showPatternError()
    }

    private fun showPatternError() {
        val input = binding.contextualRulePattern.value
        binding.contextualRulePatternHint.error = if (rule.matchType == MATCH_TITLE_REGEX && input.isNotEmpty()) {
            ContextualRuleEvaluator.regexError(input)?.let { activity.getString(R.string.contextual_rule_bad_regex, it) }
        } else {
            null
        }
    }

    private fun pickCalendar() {
        val items = ArrayList<RadioItem>()
        items.add(RadioItem(ALL_CALENDARS, activity.getString(R.string.contextual_rule_all_calendars)))
        calendars.forEach { items.add(RadioItem(it.id!!.toInt(), it.getDisplayTitle(), it.id!!)) }

        val checked = rule.calendarId?.toInt() ?: ALL_CALENDARS
        RadioGroupDialog(activity, items, checked) {
            rule.calendarId = if (it == ALL_CALENDARS) null else it as Long
            showCalendar()
        }
    }

    private fun showCalendar() {
        val title = calendars.firstOrNull { it.id == rule.calendarId }?.getDisplayTitle()
        binding.contextualRuleCalendar.setText(title ?: activity.getString(R.string.contextual_rule_all_calendars))
    }

    /** Validates the form into [rule]. Returns false (after telling the user why) if it can't be saved. */
    private fun applyInput(): Boolean {
        val input = binding.contextualRulePattern.value
        when (rule.matchType) {
            MATCH_TITLE_CONTAINS, MATCH_TITLE_REGEX -> {
                if (input.isBlank()) {
                    activity.toast(R.string.contextual_rule_needs_text)
                    return false
                }

                if (rule.matchType == MATCH_TITLE_REGEX && ContextualRuleEvaluator.regexError(input) != null) {
                    showPatternError()
                    return false
                }

                rule.pattern = input
            }

            MATCH_DURATION_OVER -> {
                rule.pattern = ContextualRuleText.hoursInputToPattern(input) ?: run {
                    activity.toast(R.string.contextual_rule_needs_hours)
                    return false
                }
            }

            MATCH_ALL -> {
                // an unscoped MATCH_ALL would turn the entire calendar into background
                if (rule.calendarId == null) {
                    activity.toast(R.string.contextual_rule_needs_calendar)
                    return false
                }
                rule.pattern = ""
            }
        }
        return true
    }

    companion object {
        private const val ALL_CALENDARS = -1
    }
}
