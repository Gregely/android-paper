package com.paperscreen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * Shows and hides a block of settings by animating its height (and fading it), so what's
 * below slides smoothly instead of jumping. The page scrolls as one, so only the block's own
 * height changes.
 */
object Reveal {

    private const val DURATION_MS = 220L
    private val EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun set(view: View, shown: Boolean, animate: Boolean = true) {
        val running = view.getTag(R.id.tag_reveal_animator) as? ValueAnimator
        running?.cancel()
        val visible = view.visibility == View.VISIBLE
        val parent = view.parent as? ViewGroup
        val width = parent?.let { it.width - it.paddingLeft - it.paddingRight - margins(view) } ?: 0

        if (!animate || width <= 0 || !view.isAttachedToWindow || (running == null && visible == shown)) {
            view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            view.alpha = 1f
            view.visibility = if (shown) View.VISIBLE else View.GONE
            view.requestLayout()
            return
        }

        val from = if (visible) view.height else 0
        val to = if (shown) {
            view.visibility = View.VISIBLE
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            view.measuredHeight
        } else {
            0
        }
        val fromAlpha = if (visible) view.alpha else 0f
        view.layoutParams.height = from
        view.requestLayout()

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                view.layoutParams.height = (from + (to - from) * t).toInt()
                view.alpha = fromAlpha + ((if (shown) 1f else 0f) - fromAlpha) * t
                view.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.setTag(R.id.tag_reveal_animator, null)
                    if (cancelled) return // The next animation carries on from here.
                    view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.alpha = 1f
                    if (!shown) view.visibility = View.GONE
                    view.requestLayout()
                }
            })
        }
        view.setTag(R.id.tag_reveal_animator, animator)
        animator.start()
    }

    private fun margins(view: View): Int {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return 0
        return params.leftMargin + params.rightMargin
    }
}
