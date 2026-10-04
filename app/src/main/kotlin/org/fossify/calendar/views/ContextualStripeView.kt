package org.fossify.calendar.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fossify.calendar.R
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.getWeeklyViewItemHeight
import org.fossify.calendar.helpers.ContextualLabelLayout
import org.fossify.calendar.helpers.ContextualStripeOverlap
import org.fossify.calendar.models.ContextualStripe
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor

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
    private val showLabels = context.config.labelContextualStripes
    private var stripes = emptyList<ContextualStripe>()
    private var coveredBy = emptyList<List<Int>>()

    // labels are drawn outside the translucent layer, so they stay readable
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size)
        color = ColorUtils.setAlphaComponent(context.getProperTextColor(), LABEL_ALPHA)
    }
    private val labelPadding = resources.getDimension(org.fossify.commons.R.dimen.small_margin)
    private val labelLineHeight = labelPaint.fontSpacing
    private val minTouchHeight = resources.getDimension(R.dimen.contextual_label_min_touch_height)
    // where each stripe's label was last drawn, by stripe index; null = not drawn
    private var labelBounds = emptyList<ContextualStripe.Bounds?>()
    private var visibleTop = 0f
    private var visibleBottom = Float.MAX_VALUE

    // one alpha for every stripe; overlaps are tinted once, by the top stripe (§3.12)
    private val stripeAlpha = run {
        val isDarkBackground = ColorUtils.calculateLuminance(context.getProperBackgroundColor()) < 0.5
        val alpha = if (isDarkBackground) DARK_ALPHA else LIGHT_ALPHA
        (alpha * 255).toInt()
    }

    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    /** Drawn in the given order; ContextualStripeBuilder sorts them for nesting. */
    fun setStripes(newStripes: List<ContextualStripe>) {
        stripes = newStripes
        labelBounds = emptyList()
        coveredBy = ContextualStripeOverlap.coveredBy(newStripes)
        invalidate()
    }

    /** The scrolled-into-view part of this view, in its own coordinates. Labels stick to its top. */
    fun setVisibleRange(top: Float, bottom: Float) {
        if (top != visibleTop || bottom != visibleBottom) {
            visibleTop = top
            visibleBottom = bottom
            if (showLabels && stripes.isNotEmpty()) {
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        labelBounds = emptyList()
        if (stripes.isEmpty()) {
            return
        }

        val rowHeight = context.getWeeklyViewItemHeight()
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val allBounds = stripes.map { it.bounds(width, daysCount, rowHeight, inset, isRtl) }

        // no saveLayer: a full-height offscreen layer was re-rendered on every frame of a week
        // swipe and doubled the janky frames (8c.2). Instead each stripe is drawn translucent
        // with the stripes on top of it clipped out, so overlaps don't darken. Rect clips are
        // cheap; they leave the top stripe's rounded corners (4dp) untinted, which is invisible
        // at this alpha
        stripes.forEachIndexed { i, stripe ->
            val bounds = allBounds[i]
            paint.color = stripe.color
            paint.alpha = stripeAlpha
            val covers = coveredBy.getOrNull(i).orEmpty()
            if (covers.isNotEmpty()) {
                canvas.save()
                covers.forEach { j ->
                    val top = allBounds[j]
                    canvas.clipOutRect(top.left, top.top, top.right, top.bottom)
                }
            }
            canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, cornerRadius, cornerRadius, paint)
            if (covers.isNotEmpty()) {
                canvas.restore()
            }
        }

        if (showLabels) {
            drawLabels(canvas, allBounds)
        }
    }

    /** The stripe whose drawn label is at (x, y) in this view's coordinates, if any. */
    fun stripeWithLabelAt(x: Float, y: Float): ContextualStripe? {
        return ContextualLabelLayout.hitTest(labelBounds, x, y, minTouchHeight)?.let { stripes.getOrNull(it) }
    }

    private fun drawLabels(canvas: Canvas, allBounds: List<ContextualStripe.Bounds>) {
        val items = stripes.mapIndexed { i, stripe -> ContextualLabelLayout.Item(stripe.dayIndex, allBounds[i]) }
        val tops = ContextualLabelLayout.place(items, visibleTop, visibleBottom, labelLineHeight, labelPadding)
        val baselineOffset = -labelPaint.fontMetrics.ascent
        val drawn = arrayOfNulls<ContextualStripe.Bounds>(stripes.size)

        tops.forEachIndexed { i, top ->
            val title = stripes[i].title
            if (top == null || title.isBlank()) {
                return@forEachIndexed
            }

            val bounds = allBounds[i]
            val available = bounds.right - bounds.left - 2 * labelPadding
            if (available <= 0f) {
                return@forEachIndexed
            }

            val text = TextUtils.ellipsize(title, labelPaint, available, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, bounds.left + labelPadding, top + baselineOffset, labelPaint)
            drawn[i] = ContextualStripe.Bounds(bounds.left, top, bounds.right, top + labelLineHeight)
        }
        labelBounds = drawn.toList()
    }

    companion object {
        // plan §4 decision 7 defaults; tuned on device in Phase 8
        private const val LIGHT_ALPHA = 0.15f
        private const val DARK_ALPHA = 0.20f
        private const val LABEL_ALPHA = 0xCC
    }
}
