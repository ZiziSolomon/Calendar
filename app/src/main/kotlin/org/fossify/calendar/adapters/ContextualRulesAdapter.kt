package org.fossify.calendar.adapters

import android.view.Menu
import android.view.View
import android.view.ViewGroup
import org.fossify.calendar.R
import org.fossify.calendar.activities.SimpleActivity
import org.fossify.calendar.databinding.ItemContextualRuleBinding
import org.fossify.calendar.helpers.ContextualRulesHelper
import org.fossify.calendar.models.ContextualRule
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.views.MyRecyclerView

class ContextualRulesAdapter(
    activity: SimpleActivity,
    val rows: ArrayList<Row>,
    recyclerView: MyRecyclerView,
    val onRulesDeleted: () -> Unit,
    val onShowMatches: (Row) -> Unit,
    itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    /** Display text is resolved up front on a background thread (it can need an event lookup). */
    // scope is the calendar name (or "All calendars"), kept apart so the matches dialog can reuse it
    data class Row(val rule: ContextualRule, val title: String, val scope: String, val subtitle: String)

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_contextual_rules

    override fun prepareActionMode(menu: Menu) {
        menu.findItem(R.id.cab_show_matches).isVisible = isOneItemSelected()
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_delete -> ConfirmationDialog(activity) { deleteSelected() }
            R.id.cab_show_matches -> rows.firstOrNull { selectedKeys.contains(it.rule.id?.toInt()) }?.let {
                onShowMatches(it)
                finishActMode()
            }
        }
    }

    override fun getSelectableItemCount() = rows.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = rows.getOrNull(position)?.rule?.id?.toInt()

    override fun getItemKeyPosition(key: Int) = rows.indexOfFirst { it.rule.id?.toInt() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemContextualRuleBinding.inflate(activity.layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.bindView(row.rule, allowSingleClick = true, allowLongClick = true) { itemView, _ ->
            setupView(itemView, row)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = rows.size

    private fun setupView(view: View, row: Row) {
        ItemContextualRuleBinding.bind(view).apply {
            contextualRuleFrame.isSelected = selectedKeys.contains(row.rule.id?.toInt())
            contextualRuleTitle.text = row.title
            contextualRuleTitle.setTextColor(textColor)
            contextualRuleSubtitle.text = row.subtitle
            contextualRuleSubtitle.setTextColor(textColor)

            contextualRuleEnabled.setOnCheckedChangeListener(null)
            contextualRuleEnabled.isChecked = row.rule.enabled
            contextualRuleEnabled.setOnCheckedChangeListener { _, isChecked ->
                row.rule.enabled = isChecked
                ContextualRulesHelper(activity).saveRule(row.rule)
            }
        }
    }

    private fun deleteSelected() {
        val toDelete = rows.filter { selectedKeys.contains(it.rule.id?.toInt()) }
        val positions = getSelectedItemPositions()
        rows.removeAll(toDelete.toSet())
        removeSelectedItems(positions)
        ContextualRulesHelper(activity).deleteRules(toDelete.map { it.rule }) {
            activity.runOnUiThread { onRulesDeleted() }
        }
    }
}
