package org.fossify.calendar.views

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fossify.calendar.R
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.models.Event
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

/**
 * Day view's stand-in for week view's stripes. Day view is a flat list with no time axis, so
 * there's no canvas to stripe (docs/CONTEXTUAL_EVENTS.md §2); contextual events become a row
 * of small chips under the date header instead. Hidden when there are none.
 */
class ContextualBandView(context: Context, attrs: AttributeSet) : HorizontalScrollView(context, attrs) {
    private val holder = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val chipSpacing = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
    private val chipPaddingH = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_margin)
    private val chipPaddingV = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.tiny_margin)
    private val cornerRadius = resources.getDimension(R.dimen.contextual_stripe_corner_radius) * 2

    init {
        isHorizontalScrollBarEnabled = false
        addView(holder)
    }

    fun setEvents(events: List<Event>, onClick: (Event) -> Unit) {
        holder.removeAllViews()
        beVisibleIf(events.isNotEmpty())

        val textColor = context.getProperTextColor()
        val isDarkBackground = ColorUtils.calculateLuminance(context.getProperBackgroundColor()) < 0.5
        // same tint strength as the week view stripes, so the two views read as one feature
        val alpha = if (isDarkBackground) 0.20f else 0.15f

        events.forEachIndexed { index, event ->
            val eventColor = if (event.color == 0) context.getProperPrimaryColor() else event.color
            val chip = TextView(context).apply {
                text = label(event) { Formatter.getTimeFromTS(context, it) }
                contentDescription = text
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size))
                maxLines = 1
                setPadding(chipPaddingH, chipPaddingV, chipPaddingH, chipPaddingV)
                background = GradientDrawable().apply {
                    this.cornerRadius = this@ContextualBandView.cornerRadius
                    setColor(ColorUtils.setAlphaComponent(eventColor, (alpha * 255).toInt()))
                }
                setOnClickListener { onClick(event) }
            }

            val params = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            if (index > 0) {
                params.marginStart = chipSpacing
            }
            holder.addView(chip, params)
        }
    }

    companion object {
        /**
         * "Title" for all-day and multi-day contexts, "Title · 09:00 – 17:00" for ones that start
         * and end on the same day. Pure, so it's testable without a device.
         */
        fun label(event: Event, zone: DateTimeZone = DateTimeZone.getDefault(), formatTime: (Long) -> String): String {
            if (event.getIsAllDay()) {
                return event.title
            }

            val sameDay = DateTime(event.startTS * 1000L, zone).toLocalDate() == DateTime(event.endTS * 1000L, zone).toLocalDate()
            return if (sameDay) {
                "${event.title} · ${formatTime(event.startTS)} – ${formatTime(event.endTS)}"
            } else {
                event.title
            }
        }
    }
}
