package org.fossify.calendar.views

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fossify.calendar.R
import org.fossify.calendar.helpers.ContextualKey
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor

/**
 * The key under the week and month grids: a swatch styled like a stripe, then the context's
 * name. Tapping an entry opens that context. Hidden when nothing contextual is on screen.
 */
class ContextualKeyView(context: Context, attrs: AttributeSet) : HorizontalScrollView(context, attrs) {
    private val holder = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    }
    private val entrySpacing = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.tiny_margin)
    private val entryPadding = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
    private val swatchSize = resources.getDimensionPixelSize(R.dimen.contextual_key_swatch_size)
    private val swatchStroke = resources.getDimensionPixelSize(R.dimen.contextual_key_swatch_stroke)
    private val cornerRadius = resources.getDimension(R.dimen.contextual_stripe_corner_radius)
    private val selectableBackground = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(holder)
    }

    /**
     * With [reserveSpace], an empty key stays invisible but keeps its height, so the grid above
     * doesn't jump each time you swipe between a week with contexts and one without.
     */
    fun setEntries(entries: List<ContextualKey.Entry>, reserveSpace: Boolean = false, onClick: (ContextualKey.Entry) -> Unit) {
        holder.removeAllViews()
        visibility = when {
            entries.isNotEmpty() -> VISIBLE
            reserveSpace -> INVISIBLE
            else -> GONE
        }

        val textColor = context.getProperTextColor()
        val isDarkBackground = ColorUtils.calculateLuminance(context.getProperBackgroundColor()) < 0.5
        // the stripes' own tint, with a solid edge so a pale colour still reads at swatch size
        val alpha = ((if (isDarkBackground) 0.20f else 0.15f) * 255).toInt()

        entries.forEachIndexed { index, entry ->
            val swatch = GradientDrawable().apply {
                cornerRadius = this@ContextualKeyView.cornerRadius
                setColor(ColorUtils.setAlphaComponent(entry.color, alpha))
                setStroke(swatchStroke, entry.color)
                setSize(swatchSize, swatchSize)
            }

            val item = TextView(context).apply {
                text = entry.title.ifBlank { context.getString(R.string.contextual_a11y_untitled) }
                contentDescription = text
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size))
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelativeWithIntrinsicBounds(swatch, null, null, null)
                compoundDrawablePadding = entryPadding
                setPadding(entryPadding, entryPadding, entryPadding, entryPadding)
                background = context.getDrawable(selectableBackground)
                setOnClickListener { onClick(entry) }
            }

            val params = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            if (index > 0) {
                params.marginStart = entrySpacing
            }
            holder.addView(item, params)
        }
    }
}
