package com.paperscreen

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * The settings page's look: the home screen's paper and ink, tinted by warmth exactly as the
 * filter tints its output, in the home screen's font.
 *
 * Views carry their role in `android:tag` (set by the layout's styles): header, summary,
 * hint, value, note, rule, options, box, button, link. Anything else is plain ink.
 */
class SettingsPage(private val density: Float) {

    /** The page's greys at one warmth. */
    class Palette(warmth: Int) {
        val paper = EinkFilter.tint(0xFF, warmth)
        val ink = EinkFilter.tint(0x33, warmth)
        val secondary = EinkFilter.tint(0x75, warmth)
        val label = EinkFilter.tint(0x8A, warmth)
        val rule = EinkFilter.tint(0xDA, warmth)
        /** The reset arrow: faint at the default, darker once there's something to reset. */
        val resetIdle = EinkFilter.tint(0xCC, warmth)
        val resetActive = EinkFilter.tint(0x5A, warmth)
        val highlight = withAlpha(ink, 0x1F)
    }

    var palette = Palette(PaperSettings.DEFAULT_WARMTH)
        private set
    var typeface: Typeface = HomeFont.DEFAULT.typeface()
        private set

    private var appliedWarmth = -1
    private var appliedFont: HomeFont? = null

    /** Restyles [root] if the warmth or font changed; returns whether it did. */
    fun apply(root: View, warmth: Int, font: HomeFont, force: Boolean = false): Boolean {
        if (!force && warmth == appliedWarmth && font == appliedFont) return false
        appliedWarmth = warmth
        appliedFont = font
        palette = Palette(warmth)
        typeface = font.typeface()
        style(root)
        return true
    }

    /** Styles [view] and everything in it with the current palette and font. */
    fun style(view: View) {
        val p = palette
        when (view.tag) {
            ROLE_RULE -> view.setBackgroundColor(p.rule)
            ROLE_OPTIONS -> view.backgroundTintList = ColorStateList.valueOf(p.rule)
            ROLE_BOX -> view.background = GradientDrawable().apply {
                cornerRadius = CORNER_DP * density
                setStroke(HAIRLINE_PX, p.rule)
            }
        }
        when (view) {
            is ImageButton -> Unit // Reset arrows are coloured by their slider.
            is SeekBar -> {
                view.progressTintList = ColorStateList.valueOf(p.ink)
                // The track drawable keeps its own 30% alpha, so this reads as a light grey.
                view.progressBackgroundTintList = ColorStateList.valueOf(p.ink)
                view.thumbTintList = ColorStateList.valueOf(p.ink)
            }
            is TextView -> styleText(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) style(view.getChildAt(i))
        }
    }

    private fun styleText(view: TextView) {
        val p = palette
        if (view.getTag(R.id.tag_keep_typeface) != true) {
            view.typeface = typeface
        }
        view.setTextColor(
            when (view.tag) {
                ROLE_HEADER -> p.label
                ROLE_SUMMARY, ROLE_HINT, ROLE_VALUE, ROLE_NOTE -> p.secondary
                else -> p.ink
            },
        )
        when (view) {
            is Switch -> styleSwitch(view)
            is RadioButton -> {
                view.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(p.ink, p.secondary),
                )
            }
            is Button -> when (view.tag) {
                ROLE_BUTTON -> view.background = ripple(
                    GradientDrawable().apply {
                        cornerRadius = CORNER_DP * density
                        setStroke(HAIRLINE_PX, p.rule)
                    },
                )
                else -> {
                    // A text link: underlined, like a reference in a book.
                    view.paintFlags = view.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    view.background = ripple(null)
                }
            }
        }
    }

    /**
     * An outline switch in ink: a hairline pill with a dark knob when off, a filled pill with
     * a paper knob when on. Built once per switch and recoloured through its tints.
     */
    private fun styleSwitch(view: Switch) {
        val p = palette
        if (view.getTag(R.id.tag_styled_switch) != true) {
            view.setTag(R.id.tag_styled_switch, true)
            view.trackDrawable = StateListDrawable().apply {
                setEnterFadeDuration(SWITCH_FADE_MS)
                setExitFadeDuration(SWITCH_FADE_MS)
                addState(intArrayOf(android.R.attr.state_checked), track(filled = true))
                addState(intArrayOf(), track(filled = false))
            }
            // A layer inset rather than an InsetDrawable: Switch treats a drawable's padding as
            // part of the knob's travel, which would push the knob against the pill's end.
            view.thumbDrawable = LayerDrawable(
                arrayOf(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.BLACK)
                        setSize(dp(THUMB_DP), dp(THUMB_DP))
                    },
                ),
            ).apply {
                val inset = dp(THUMB_INSET_DP)
                setLayerInset(0, inset, inset, inset, inset)
            }
            view.switchMinWidth = dp(TRACK_WIDTH_DP)
            view.splitTrack = false // Draw the knob on the track, not in a hole cut out of it.
            view.background = null
        }
        view.trackTintList = ColorStateList.valueOf(p.ink)
        view.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(p.paper, p.ink),
        )
    }

    private fun track(filled: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(TRACK_HEIGHT_DP) / 2f
        setSize(dp(TRACK_WIDTH_DP), dp(TRACK_HEIGHT_DP))
        setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.BLACK)
        setColor(if (filled) Color.BLACK else Color.TRANSPARENT)
    }

    /** A press highlight in faint ink, filling the view's rounded bounds, over [content]. */
    private fun ripple(content: GradientDrawable?): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = CORNER_DP * density
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(palette.highlight), content, mask)
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        const val ROLE_HEADER = "header"
        const val ROLE_SUMMARY = "summary"
        const val ROLE_HINT = "hint"
        const val ROLE_VALUE = "value"
        const val ROLE_NOTE = "note"
        const val ROLE_RULE = "rule"
        const val ROLE_OPTIONS = "options"
        const val ROLE_BOX = "box"
        const val ROLE_BUTTON = "button"

        /** Hairlines are one physical pixel, like the home screen's rules. */
        private const val HAIRLINE_PX = 1
        private const val CORNER_DP = 4
        // The switch is two knob-widths wide: 44 × 22 dp.
        private const val TRACK_WIDTH_DP = 44
        private const val TRACK_HEIGHT_DP = 22
        private const val THUMB_DP = 14
        private const val THUMB_INSET_DP = 4
        private const val SWITCH_FADE_MS = 120

        fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
