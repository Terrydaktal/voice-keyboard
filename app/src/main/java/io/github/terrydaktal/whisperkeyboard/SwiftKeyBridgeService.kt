package io.github.terrydaktal.whisperkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Watches for the calling keyboard to reopen after [SpeechBridgeActivity] returns, captures
 * its visible keyboard height, and switches the active IME to Voice Keyboard.
 */
class SwiftKeyBridgeService : AccessibilityService() {
    private class Session(
        val callerIme: String,
        val voiceIme: String,
        val switchAfter: Long,
        val deadline: Long,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val scan = Runnable { scanKeyboard() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    internal fun startDictation(caller: String) {
        if (session != null) return
        val currentIme = KeyboardSwitch.selected(this) ?: return
        if (KeyboardSwitch.packageOf(currentIme) != caller) return
        if (!KeyboardSwitch.canWrite(this)) {
            notifyUser(R.string.grant_permission)
            return
        }
        val voiceIme = KeyboardSwitch.enabled(this)
            .firstOrNull { it.packageName == packageName }
        if (voiceIme == null) {
            notifyUser(R.string.enable_voice_keyboard)
            return
        }
        if (!KeyboardSwitch.microphoneAllowed(this)) {
            notifyUser(R.string.voice_keyboard_microphone_needed)
            return
        }
        val now = SystemClock.uptimeMillis()
        session = Session(
            callerIme = currentIme,
            voiceIme = voiceIme.id,
            switchAfter = now + CALLER_RESULT_GRACE_MS,
            deadline = now + CALLER_RESUME_TIMEOUT_MS,
        )
        handler.post(scan)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (session == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            handler.removeCallbacks(scan)
            handler.post(scan)
        }
    }

    private fun scanKeyboard() {
        val active = session ?: return
        if (KeyboardSwitch.selected(this) != active.callerIme) {
            endSession()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now < active.switchAfter) {
            handler.postDelayed(scan, POLL_INTERVAL_MS)
            return
        }
        val callerWindow = inputMethodWindow(KeyboardSwitch.packageOf(active.callerIme))
        if (callerWindow == null) {
            if (now >= active.deadline) {
                endSession(R.string.editor_unavailable)
            } else {
                handler.postDelayed(scan, POLL_INTERVAL_MS)
            }
            return
        }
        val measuredHeight = Rect().also(callerWindow.first::getBoundsInScreen).height()
        val validHeight = if (measuredHeight in 1 until resources.displayMetrics.heightPixels) {
            measuredHeight
        } else {
            0
        }
        KeyboardLayoutStore.saveCallerKeyboardState(this, active.callerIme, validHeight)
        if (!KeyboardSwitch.select(this, active.voiceIme)) {
            endSession(R.string.switch_failed)
            return
        }
        Log.d(TAG, "Switched from ${active.callerIme} to Voice Keyboard (height=$validHeight)")
        endSession()
    }

    private fun inputMethodWindow(
        packageName: String?,
    ): Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>? =
        windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                (window to root).takeIf { root.packageName?.toString() == packageName }
            }
            .firstOrNull()

    private fun endSession(message: Int? = null) {
        session = null
        handler.removeCallbacks(scan)
        if (message != null) notifyUser(message)
    }

    private fun notifyUser(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {
        if (session != null) endSession()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (connected !== this) return
        connected = null
        if (session != null) endSession()
    }

    companion object {
        internal var connected: SwiftKeyBridgeService? = null
            private set

        private const val TAG = "SwiftKeyBridge"
        private const val CALLER_RESULT_GRACE_MS = 250L
        private const val CALLER_RESUME_TIMEOUT_MS = 4_000L
        private const val POLL_INTERVAL_MS = 150L
    }
}
