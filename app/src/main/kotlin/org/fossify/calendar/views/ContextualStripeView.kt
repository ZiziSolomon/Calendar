package org.fossify.calendar.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fossify.calendar.R
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.getWeeklyViewItemHeight
import org.fossify.calendar.models.ContextualStripe
import org.fossify.commons.extensions.getProperBackgroundColor

/**
 * Background layer of the week view, sitting between the hour grid and the event columns.
 * Contextual events are drawn here instead of as event markers, so they never take part in
 * the collision packing that pushes overlapping real events sideways.
 * Kept as its own view rather than threaded through WeekFragment.addEvents() to keep the
 * upstream diff small.
 */
class ContextualStripeView(context: Context, attrs: AttributeSet, defStyle: Int) : View(context, attrs, defStyle) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cornerRadius = resources.getDimension(R.dimen.contextual_stripe_corner_radius)
    private val inset = resources.getDimension(R.dimen.contextual_stripe_inset)
    private val daysCount = context.config.weeklyViewDays
    private var stripes = emptyList<ContextualStripe>()

    // the layer alpha is applied once to everything, so overlapping stripes don't darken (§3.12)
    private val layerAlpha = run {
        val isDarkBackground = ColorUtils.calculateLuminance(context.getProperBackgroundColor()) < 0.5
        val alpha = if (isDarkBackground) DARK_ALPHA else LIGHT_ALPHA
        (alpha * 255).toInt()
    }

    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    /** Drawn in the given order; ContextualStripeBuilder sorts them for nesting. */
    fun setStripes(newStripes: List<ContextualStripe>) {
        stripes = newStripes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (stripes.isEmpty()) {
            return
        }

        val rowHeight = context.getWeeklyViewItemHeight()
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val saveCount = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), layerAlpha)
        for (stripe in stripes) {
            val bounds = stripe.bounds(width, daysCount, rowHeight, inset, isRtl)
            paint.color = stripe.color or OPAQUE  // only the layer carries alpha
            canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, cornerRadius, cornerRadius, paint)
        }
        canvas.restoreToCount(saveCount)
    }

    companion object {
        // plan §4 decision 7 defaults; tuned on device in Phase 8
        private const val LIGHT_ALPHA = 0.15f
        private const val DARK_ALPHA = 0.20f
        private const val OPAQUE = 0xFF000000.toInt()
    }
}
