package com.paperscreen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The drawer's vertical A–Z index, like a contacts app. Shows one letter per section,
 * spread over its height (shrinking to fit when space is short). Tapping, or sliding along
 * it, reports the letter under the finger through [onLetter].
 */
class AlphabetStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var letters: List<String> = emptyList()
        set(value) {
            field = value
            contentDescription = context.getString(R.string.home_index_description)
            invalidate()
        }

    var onLetter: ((String) -> Unit)? = null

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    private val maxTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var lastReported: String? = null

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val usable = height - paddingTop - paddingBottom
        val cell = usable / letters.size.toFloat()
        paint.textSize = min(maxTextSize, cell * 0.8f)
        val x = width / 2f
        val baselineOffset = -(paint.ascent() + paint.descent()) / 2
        letters.forEachIndexed { i, letter ->
            canvas.drawText(letter, x, paddingTop + cell * (i + 0.5f) + baselineOffset, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // The list itself stays fully scrollable.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val usable = (height - paddingTop - paddingBottom).coerceAtLeast(1)
                val index = ((event.y - paddingTop) / usable * letters.size).toInt().coerceIn(0, letters.lastIndex)
                val letter = letters[index]
                if (letter != lastReported) {
                    lastReported = letter
                    onLetter?.invoke(letter)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lastReported = null
        }
        return true
    }
}
