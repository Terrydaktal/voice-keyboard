package io.github.terrydaktal.whisperkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class MicrophonePanel(
    context: Context,
    onMicrophone: () -> Unit,
    onReturnKeyboard: () -> Unit,
    onBackendMenu: (View) -> Unit,
    onDeleteChar: () -> Unit,
    onDeleteWord: () -> Unit,
    onEnter: () -> Unit,
    onPendingTap: (View) -> Unit,
    onPendingLongPress: (View) -> Unit,
) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
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
    private val returnButton: Button
    private val backendButton: Button
    private val pendingButton: Button
    private val backspaceButton: ImageButton
    private val enterButton: ImageButton

    private var repeatingWordDelete = false
    private var backspaceTouchActive = false
    private val wordDeleteRepeater = object : Runnable {
        override fun run() {
            if (!backspaceTouchActive) return
            repeatingWordDelete = true
            backspaceButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDeleteWord()
            main.postDelayed(this, WORD_DELETE_REPEAT_MS)
        }
    }

    var keyboardHeight: Int = KeyboardLayoutStore.keyboardHeight(context)
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

        val controlColor = if (dark) 0xFFA0DCD8.toInt() else 0xFF146B68.toInt()
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        returnButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            id = R.id.swiftkey_button
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(controlColor)
            setOnClickListener { onReturnKeyboard() }
        }
        val returnContainer = FrameLayout(context).apply {
            addView(
                returnButton,
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.START or Gravity.CENTER_VERTICAL),
            )
        }
        topBar.addView(
            returnContainer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        backendButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            id = R.id.dictation_settings_button
            isAllCaps = false
            maxLines = 1
            setTextColor(controlColor)
            setOnClickListener(onBackendMenu)
        }
        topBar.addView(
            backendButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)),
        )

        addView(
            topBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(6)
            },
        )

        val keyBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(if (dark) 0xFF282C32.toInt() else 0xFFDCE5E5.toInt())
        }
        val keyRipple = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
            keyBg,
            null,
        )
        val slopPx = dp(16).toFloat()
        backspaceButton = object : ImageButton(context) {
            override fun performClick(): Boolean {
                super.performClick()
                onDeleteChar()
                return true
            }
        }.apply {
            id = R.id.backspace_button
            background = keyRipple
            setImageResource(R.drawable.ic_backspace)
            imageTintList = ColorStateList.valueOf(if (dark) 0xFFE3ECEC.toInt() else 0xFF1F2B2B.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.backspace_description)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        backspaceTouchActive = true
                        repeatingWordDelete = false
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        main.removeCallbacks(wordDeleteRepeater)
                        main.postDelayed(wordDeleteRepeater, WORD_DELETE_INITIAL_DELAY_MS)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val inside = event.x >= -slopPx &&
                            event.x <= view.width + slopPx &&
                            event.y >= -slopPx &&
                            event.y <= view.height + slopPx
                        if (!inside && backspaceTouchActive) {
                            stopBackspaceHold()
                            view.isPressed = false
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val shouldTap = backspaceTouchActive && !repeatingWordDelete
                        stopBackspaceHold()
                        view.isPressed = false
                        if (shouldTap) view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        stopBackspaceHold()
                        view.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }

        val enterBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(0xFF146B68.toInt())
        }
        val enterRipple = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), enterBg, null)
        enterButton = ImageButton(context).apply {
            id = R.id.enter_button
            background = enterRipple
            setImageResource(R.drawable.ic_enter)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.enter_description)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onEnter()
            }
        }

        // Right-edge action column matching SwiftKey: Backspace on row 3, Enter on bottom-right row 4.
        val rightKeys = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(
                backspaceButton,
                LinearLayout.LayoutParams(dp(58), dp(48)).apply {
                    bottomMargin = dp(10)
                },
            )
            addView(
                enterButton,
                LinearLayout.LayoutParams(dp(58), dp(48)),
            )
        }
        addView(
            rightKeys,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(10)
                bottomMargin = dp(8)
            },
        )

        val pendingBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(if (dark) 0xFF33261A.toInt() else 0xFFF3E2D0.toInt())
        }
        val pendingRipple = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
            pendingBg,
            null,
        )
        pendingButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            id = R.id.pending_retry_button
            background = pendingRipple
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (dark) 0xFFFFC98A.toInt() else 0xFF8C4600.toInt())
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            setOnClickListener { onPendingTap(it) }
            setOnLongClickListener {
                onPendingLongPress(it)
                true
            }
        }
        addView(
            pendingButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.START or Gravity.BOTTOM).apply {
                marginStart = dp(10)
                bottomMargin = dp(8)
            },
        )

        updateReturnKeyboard(KeyboardLayoutStore.returnKeyboardLabel(context))
        updateBackend(DictationSettings.backend(context))
    }

    override fun onDetachedFromWindow() {
        stopBackspaceHold()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resolveSize(keyboardHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    fun updateReturnKeyboard(label: String?) {
        if (label.isNullOrBlank()) {
            returnButton.setText(R.string.return_keyboard_fallback)
            returnButton.contentDescription = context.getString(R.string.back_to_keyboard_fallback)
        } else {
            returnButton.text = context.getString(R.string.return_keyboard_button, label)
            returnButton.contentDescription = context.getString(R.string.back_to_keyboard, label)
        }
    }

    fun updateBackend(backend: DictationBackend) {
        backendButton.setText(backend.buttonLabel)
        backendButton.contentDescription = context.getString(
            R.string.backend_button_description,
            context.getString(backend.menuLabel),
        )
    }

    fun updateEnterAction(labelRes: Int) {
        enterButton.contentDescription = context.getString(labelRes)
    }

    fun updatePending(count: Int, enabled: Boolean) {
        pendingButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        pendingButton.isEnabled = enabled
        pendingButton.alpha = if (enabled) 1f else 0.55f
        if (count > 0) {
            pendingButton.text = context.getString(R.string.pending_retry_button, count)
            pendingButton.contentDescription = context.getString(R.string.pending_retry_description, count)
        }
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

    private fun stopBackspaceHold() {
        backspaceTouchActive = false
        repeatingWordDelete = false
        main.removeCallbacks(wordDeleteRepeater)
    }

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
        private const val WORD_DELETE_INITIAL_DELAY_MS = 380L
        private const val WORD_DELETE_REPEAT_MS = 140L
    }
}
