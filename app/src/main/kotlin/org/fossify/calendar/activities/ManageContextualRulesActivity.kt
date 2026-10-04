package org.fossify.calendar.activities

import android.os.Bundle
import org.fossify.calendar.R
import org.fossify.calendar.adapters.ContextualRulesAdapter
import org.fossify.calendar.databinding.ActivityManageContextualRulesBinding
import org.fossify.calendar.dialogs.EditContextualRuleDialog
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.helpers.CALENDAR_ID
import org.fossify.calendar.helpers.ContextualRulesHelper
import org.fossify.calendar.models.ContextualRule
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon

/**
 * Lists contextual rules. Opened from Settings (all rules), or from a calendar's menu with
 * [CALENDAR_ID] set, in which case it shows that calendar's rules and new rules default to it.
 */
class ManageContextualRulesActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityManageContextualRulesBinding::inflate)
    private val helper by lazy { ContextualRulesHelper(this) }
    private var calendarId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        calendarId = intent.getLongExtra(CALENDAR_ID, -1L).takeIf { it != -1L }

        binding.manageContextualRulesToolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.add_contextual_rule) {
                showEditDialog(null)
                true
            } else {
                false
            }
        }

        setupEdgeToEdge(padBottomSystem = listOf(binding.manageContextualRulesList))
        setupMaterialScrollListener(binding.manageContextualRulesList, binding.manageContextualRulesAppbar)
        updateTextColors(binding.manageContextualRulesCoordinator)
        loadRules()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageContextualRulesAppbar, NavigationIcon.Arrow)
    }

    private fun showEditDialog(rule: ContextualRule?) {
        EditContextualRuleDialog(this, rule, calendarId) {
            loadRules()
        }
    }

    private fun loadRules() {
        helper.getRules { allRules ->
            // already on a background thread: resolve calendar names and event titles here
            val rules = if (calendarId == null) allRules else allRules.filter { it.calendarId == calendarId }
            val calendarTitles = calendarsDB.getCalendars().associate { it.id to it.getDisplayTitle() }
            val allCalendars = getString(R.string.contextual_rule_all_calendars)
            val rows = rules.map { rule ->
                val scope = rule.calendarId?.let { calendarTitles[it] } ?: allCalendars
                ContextualRulesAdapter.Row(rule, helper.describe(rule), scope)
            }

            val toolbarSubtitle = calendarId?.let { calendarTitles[it] }
            runOnUiThread { showRows(ArrayList(rows), toolbarSubtitle) }
        }
    }

    private fun showRows(rows: ArrayList<ContextualRulesAdapter.Row>, toolbarSubtitle: String?) {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.manageContextualRulesToolbar.subtitle = toolbarSubtitle
        binding.manageContextualRulesPlaceholder.beVisibleIf(rows.isEmpty())
        binding.manageContextualRulesList.adapter = ContextualRulesAdapter(
            activity = this,
            rows = rows,
            recyclerView = binding.manageContextualRulesList,
            onRulesDeleted = { binding.manageContextualRulesPlaceholder.beVisibleIf(rows.isEmpty()) }
        ) {
            showEditDialog(it as ContextualRule)
        }
    }
}
