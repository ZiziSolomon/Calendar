package org.fossify.calendar.interfaces

import org.fossify.calendar.helpers.ContextualKey

interface WeekFragmentListener {
    fun scrollTo(y: Int)

    fun updateHoursTopMargin(margin: Int)

    fun getCurrScrollY(): Int

    fun updateRowHeight(rowHeight: Int)

    fun getFullFragmentHeight(): Int

    /** The visible page's contexts, for the key under the week view. */
    fun updateContextualKey(entries: List<ContextualKey.Entry>, onClick: (ContextualKey.Entry) -> Unit)
}
