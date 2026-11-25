package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class MicrophonePanel(
    context: Context,
    onMicrophone: () -> Unit,
) : FrameLayout(context) {
    private val waveform = Waveform(context)
    private val fill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val microphone = ImageButton(context).apply {
        id = R.id.dictation_button
        background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), fill, null)
        setImageResource(R.drawable.ic_microphone)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(20), dp(20), dp(20), dp(20))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener { onMicrophone() }
    }
    private val progress = ProgressBar(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        indeterminateTintList = ColorStateList.valueOf(0xFF258B86.toInt())
        visibility = View.GONE
    }

    var keyboardHeight: Int = defaultKeyboardHeight(context)
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        setBackgroundColor(if (dark) 0xFF17191C.toInt() else 0xFFF0F4F4.toInt())
        addView(waveform, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(progress, LayoutParams(dp(98), dp(98), Gravity.CENTER))
        addView(microphone, LayoutParams(dp(76), dp(76), Gravity.CENTER))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resolveSize(keyboardHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    fun show(label: Int, enabled: Boolean, recording: Boolean, busy: Boolean) {
        microphone.contentDescription = context.getString(label)
        microphone.isEnabled = enabled
        fill.setColor(if (recording) 0xFFB93351.toInt() else 0xFF146B68.toInt())
        microphone.alpha = if (enabled) 1f else 0.65f
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        waveform.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        if (!recording) waveform.clear()
    }

    fun level(value: Float) = waveform.level(value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class Waveform(context: Context) : View(context) {
        private val levels = FloatArray(48)
        private val directions = Array(levels.size) { index ->
            val angle = index * 2 * PI / levels.size - PI / 2
            cos(angle).toFloat() to sin(angle).toFloat()
        }
        private var next = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDC627B.toInt()
            strokeWidth = 2.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun level(value: Float) {
            levels[next] = value
            next = (next + 1) % levels.size
            invalidate()
        }

        fun clear() {
            levels.fill(0f)
            next = 0
        }

        override fun onDraw(canvas: Canvas) {
            val centerX = width / 2f
            val centerY = height / 2f
            val density = resources.displayMetrics.density
            val room = minOf(centerX, centerY)
            val radius = minOf(48f * density, room - 4f * density)
            val stretch = minOf(32f * density, room - radius - 4f * density).coerceAtLeast(0f)
            directions.forEachIndexed { index, (dx, dy) ->
                val end = radius + 2f * density + stretch * levels[(next + index) % levels.size]
                canvas.drawLine(
                    centerX + radius * dx, centerY + radius * dy,
                    centerX + end * dx, centerY + end * dy, paint,
                )
            }
        }
    }

    companion object {
        private fun defaultKeyboardHeight(context: Context): Int {
            val metrics = context.resources.displayMetrics
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            return ((if (landscape) 202 else 296) * metrics.density).toInt()
        }
    }
}
