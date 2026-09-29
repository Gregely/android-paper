package com.paperscreen

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * A small context menu in the home screen's style: a few text rows on the tinted page colour,
 * separated by hairlines, in a hairline frame. No shadow or animation. It appears next to the
 * pressed item (below it, or above if there's no room) and closes on a tap outside, on Back,
 * or once a row is chosen.
 */
class PaperMenu(private val context: Context) {

    class Item(val label: String, val action: () -> Unit)

    /** How the menu lines up with the pressed row. */
    enum class Align { TEXT_START, CENTRE }

    class Colours(val paper: Int, val ink: Int, val rule: Int, val typeface: Typeface)

    private val density = context.resources.displayMetrics.density
    private var popup: PopupWindow? = null

    val isShowing: Boolean get() = popup?.isShowing == true

    fun show(anchor: View, items: List<Item>, colours: Colours, align: Align) {
        dismiss()
        if (items.isEmpty() || !anchor.isAttachedToWindow) return

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colours.paper)
                setStroke(HAIRLINE_PX, colours.rule)
                cornerRadius = CORNER_DP * density
            }
            // Keeps the rows' press highlight inside the frame's hairline.
            setPadding(HAIRLINE_PX, HAIRLINE_PX, HAIRLINE_PX, HAIRLINE_PX)
            // Hairlines between rows, drawn by the column so they don't widen the menu.
            dividerDrawable = GradientDrawable().apply {
                setColor(colours.rule)
                setSize(HAIRLINE_PX, HAIRLINE_PX)
            }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        for (item in items) {
            column.addView(row(item, colours), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val maxWidth = anchor.rootView.width - dp(32)
        column.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = column.measuredWidth.coerceIn(dp(MIN_WIDTH_DP).coerceAtMost(maxWidth), maxWidth)

        popup = PopupWindow(column, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            // Focusable and outside-touchable: a tap outside (or Back) just closes the menu.
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 0f
            animationStyle = 0 // Appears and disappears at once, like a page on e-ink.
            val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val x = when (align) {
                Align.CENTRE -> (anchor.width - width) / 2
                Align.TEXT_START -> if (rtl) anchor.width - anchor.paddingRight - width else anchor.paddingLeft
            }
            // Just below the row; PopupWindow moves it above instead when there's no room.
            // x is worked out above for both directions, so it's applied from the left edge.
            @SuppressLint("RtlHardcoded")
            showAsDropDown(anchor, x, 0, Gravity.TOP or Gravity.LEFT)
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun row(item: Item, colours: Colours) = TextView(context).apply {
        text = item.label
        textSize = 16f
        typeface = colours.typeface
        setTextColor(colours.ink)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(48)
        setPadding(dp(20), dp(10), dp(28), dp(10))
        val highlight = Color.argb(0x1A, Color.red(colours.ink), Color.green(colours.ink), Color.blue(colours.ink))
        background = RippleDrawable(ColorStateList.valueOf(highlight), null, ColorDrawable(Color.WHITE))
        setOnClickListener {
            dismiss()
            item.action()
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val HAIRLINE_PX = 1
        const val CORNER_DP = 2
        const val MIN_WIDTH_DP = 180
    }
}
