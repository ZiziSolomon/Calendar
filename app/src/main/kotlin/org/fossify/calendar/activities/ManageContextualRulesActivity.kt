package org.fossify.calendar.activities

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import org.fossify.calendar.R
import org.fossify.calendar.adapters.ContextualRulesAdapter
import org.fossify.calendar.databinding.ActivityManageContextualRulesBinding
import org.fossify.calendar.dialogs.EditContextualRuleDialog
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.helpers.getActivityToOpen
import org.fossify.calendar.helpers.CALENDAR_ID
import org.fossify.calendar.helpers.EVENT_ID
import org.fossify.calendar.helpers.EVENT_OCCURRENCE_TS
import org.fossify.calendar.helpers.IS_TASK_COMPLETED
import org.fossify.calendar.helpers.ContextualRuleUsage
import org.fossify.calendar.helpers.ContextualRulesHelper
import org.fossify.calendar.helpers.getNowSeconds
import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyTextView
import org.joda.time.DateTime

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
            val usage = ContextualRuleUsage.count(rules, getUsageWindowEvents())
            val rows = rules.mapIndexed { index, rule ->
                val scope = rule.calendarId?.let { calendarTitles[it] } ?: allCalendars
                ContextualRulesAdapter.Row(rule, helper.describe(rule), "$scope · ${describeUsage(usage[index])}")
            }

            val toolbarSubtitle = calendarId?.let { calendarTitles[it] }
            runOnUiThread { showRows(ArrayList(rows), toolbarSubtitle) }
        }
    }

    // every calendar, ignoring the main screen's filter: a rule's reach doesn't depend on what's shown
    private fun getUsageWindowEvents(): List<Event> {
        val now = getNowSeconds()
        var events = emptyList<Event>()
        eventsHelper.getEventsSync(
            now - ContextualRuleUsage.DAYS_BACK * DAY_SECONDS,
            now + ContextualRuleUsage.DAYS_AHEAD * DAY_SECONDS,
            applyTypeFilter = false
        ) { events = it }
        return events
    }

    private fun showMatches(rule: ContextualRule) {
        ensureBackgroundThread {
            val matches = ContextualRuleUsage.matches(rule, getUsageWindowEvents(), getNowSeconds())
            val title = helper.describe(rule)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    showMatchesDialog(title, matches)
                }
            }
        }
    }

    private fun showMatchesDialog(title: String, matches: List<Event>) {
        if (matches.isEmpty()) {
            toast(R.string.contextual_rule_usage_none)
            return
        }

        val padding = resources.getDimension(org.fossify.commons.R.dimen.activity_margin).toInt()
        val rowHeight = resources.getDimension(org.fossify.commons.R.dimen.normal_icon_size).toInt()
        val thisYear = DateTime.now().year
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null

        matches.take(ContextualRuleUsage.MAX_LISTED).forEach { event ->
            val start = DateTime(event.startTS * 1000L)
            // the window spans a year boundary, so say which year when it isn't this one
            val date = start.toString(if (start.year == thisYear) "EEE d MMM" else "EEE d MMM yyyy")
            list.addView(MyTextView(this).apply {
                text = getString(R.string.contextual_preview_sample, date, event.title)
                setTextColor(getProperTextColor())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                minHeight = rowHeight
                setPadding(padding, 0, padding, 0)
                background = AppCompatResources.getDrawable(this@ManageContextualRulesActivity, org.fossify.commons.R.drawable.ripple_background)
                setOnClickListener {
                    dialog?.dismiss()
                    openEvent(event)
                }
            })
        }

        val extra = matches.size - ContextualRuleUsage.MAX_LISTED
        if (extra > 0) {
            list.addView(MyTextView(this).apply {
                text = getString(R.string.contextual_rule_more_matches, extra)
                setTextColor(getProperTextColor())
                setPadding(padding, padding / 2, padding, 0)
            })
        }

        val view = ScrollView(this).apply { addView(list) }
        getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(view, this, titleText = title) { dialog = it }
            }
    }

    private fun openEvent(event: Event) {
        Intent(this, getActivityToOpen(event.isTask())).apply {
            putExtra(EVENT_ID, event.id)
            putExtra(EVENT_OCCURRENCE_TS, event.startTS)
            putExtra(IS_TASK_COMPLETED, event.isTaskCompleted())
            startActivity(this)
        }
    }

    private fun describeUsage(count: Int) = if (count == 0) {
        getString(R.string.contextual_rule_usage_none)
    } else {
        resources.getQuantityString(R.plurals.contextual_rule_usage, count, count)
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
            onRulesDeleted = { binding.manageContextualRulesPlaceholder.beVisibleIf(rows.isEmpty()) },
            onShowMatches = { showMatches(it) }
        ) {
            showEditDialog(it as ContextualRule)
        }
    }
}
