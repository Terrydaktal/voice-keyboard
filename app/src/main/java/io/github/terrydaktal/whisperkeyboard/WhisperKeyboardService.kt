package io.github.terrydaktal.whisperkeyboard

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import io.github.terrydaktal.whisperkeyboard.WhisperRecordingService.Phase

class WhisperKeyboardService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private var panel: MicrophonePanel? = null
    private var viewActive = false
    private var viewGeneration = 0L
    private var autoStartOnOpen = true
    private var passwordField = false
    private var submittedFrom: InputConnection? = null
    private val layoutPreferences by lazy { KeyboardLayoutStore.preferences(this) }
    private val onLayoutChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        panel?.keyboardHeight = KeyboardLayoutStore.keyboardHeight(this)
        panel?.updateReturnKeyboard(KeyboardLayoutStore.returnKeyboardLabel(this))
    }

    private val onStateChanged: (WhisperRecordingService.Snapshot) -> Unit = { state ->
        if (state.phase == Phase.STARTING) autoStartOnOpen = false
        if (state.phase == Phase.STARTING || state.phase == Phase.FINISHED) submittedFrom = null
        render(state.phase)
        if (state.phase == Phase.RESULT) insertTranscript(showError = false)
    }

    private val onAudioLevel: (Float) -> Unit = { level ->
        if (viewActive) panel?.level(level)
    }

    override fun onCreate() {
        super.onCreate()
        WhisperRecordingService.listeners.add(onStateChanged)
        WhisperRecordingService.levelListeners.add(onAudioLevel)
        layoutPreferences.registerOnSharedPreferenceChangeListener(onLayoutChanged)
    }

    override fun onCreateInputView(): View = MicrophonePanel(
        this,
        ::handleMic,
        ::returnToConfiguredKeyboard,
    ).also {
        panel = it
        it.updateReturnKeyboard(KeyboardLayoutStore.returnKeyboardLabel(this))
        render(WhisperRecordingService.snapshot.phase)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val opening = !viewActive || !restarting
        viewActive = true
        passwordField = isPassword(info.inputType)
        panel?.keyboardHeight = KeyboardLayoutStore.keyboardHeight(this)
        panel?.updateReturnKeyboard(KeyboardLayoutStore.returnKeyboardLabel(this))
        render(WhisperRecordingService.snapshot.phase)
        if (!opening) return

        val generation = ++viewGeneration
        main.post {
            if (!viewActive || !isInputViewShown || generation != viewGeneration) return@post
            when (WhisperRecordingService.snapshot.phase) {
                Phase.READY, Phase.FINISHED -> if (autoStartOnOpen) startWhenVisible(generation)
                Phase.RESULT -> {
                    autoStartOnOpen = false
                    insertTranscript(showError = false)
                }
                else -> autoStartOnOpen = false
            }
        }
    }

    private fun startWhenVisible(generation: Long) {
        if (passwordField || !hasMicrophonePermission()) return
        val inputView = panel ?: return
        inputView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                inputView.viewTreeObserver.removeOnPreDrawListener(this)
                // The microphone foreground service needs the IME window to be visible to Android.
                inputView.postOnAnimationDelayed({
                    if (autoStartOnOpen && viewActive && generation == viewGeneration && inputView.isShown) {
                        WhisperRecordingService.start(this@WhisperKeyboardService)
                    }
                }, WINDOW_VISIBLE_SETTLE_MS)
                return true
            }
        })
        inputView.invalidate()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        closeView()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        closeView()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        closeView()
        WhisperRecordingService.listeners.remove(onStateChanged)
        WhisperRecordingService.levelListeners.remove(onAudioLevel)
        layoutPreferences.unregisterOnSharedPreferenceChangeListener(onLayoutChanged)
        super.onDestroy()
    }

    private fun closeView() {
        viewActive = false
        viewGeneration++
    }

    private fun handleMic() {
        when (WhisperRecordingService.snapshot.phase) {
            Phase.READY, Phase.FINISHED -> {
                if (passwordField || !viewActive || !isInputViewShown) return
                if (hasMicrophonePermission()) {
                    WhisperRecordingService.start(this)
                } else {
                    startActivity(Intent(this, SetupActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(SetupActivity.REQUEST_MICROPHONE, true)
                    })
                }
            }
            Phase.RECORDING -> {
                submittedFrom = currentInputConnection
                WhisperRecordingService.stopFromKeyboard()
            }
            Phase.RETRY -> {
                if (passwordField) return
                submittedFrom = currentInputConnection
                WhisperRecordingService.retry()
            }
            Phase.RESULT -> insertTranscript(showError = true)
            Phase.STARTING, Phase.TRANSCRIBING -> Unit
        }
    }

    private fun returnToConfiguredKeyboard() {
        val keyboards = getSystemService(InputMethodManager::class.java)
        val target = KeyboardLayoutStore.resolveReturnKeyboard(this)
        if (target != null) {
            try {
                switchInputMethod(target.id)
                if (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == target.id) {
                    closeView()
                    WhisperRecordingService.deferInsertion()
                    autoStartOnOpen = true
                    return
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not switch to ${target.id}", error)
            }
        }
        keyboards.showInputMethodPicker()
    }

    private fun insertTranscript(showError: Boolean) {
        if (!viewActive || !isInputViewShown || passwordField) return
        val editor = currentInputConnection ?: return
        val needsConfirmation = WhisperRecordingService.manualInsertRequired ||
            (submittedFrom != null && editor !== submittedFrom)
        if (!showError && needsConfirmation) return
        val text = WhisperRecordingService.snapshot.transcript ?: return
        if (editor.commitText(text, 1)) {
            WhisperRecordingService.transcriptInserted()
        } else if (showError) {
            Toast.makeText(this, R.string.insert_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(phase: Phase) {
        val label = when (phase) {
            Phase.RECORDING -> R.string.stop_recording
            Phase.STARTING -> R.string.starting_recording
            Phase.TRANSCRIBING -> R.string.transcribing
            Phase.RETRY -> if (passwordField) R.string.password_field else R.string.retry_transcription
            Phase.RESULT -> if (passwordField) R.string.password_field else R.string.insert_transcript
            Phase.READY, Phase.FINISHED -> when {
                passwordField -> R.string.password_field
                !hasMicrophonePermission() -> R.string.allow_microphone
                else -> R.string.start_recording
            }
        }
        val busy = phase == Phase.STARTING || phase == Phase.TRANSCRIBING
        val enabled = !busy && (phase == Phase.RECORDING || label != R.string.password_field)
        panel?.show(label, enabled, phase == Phase.RECORDING, busy)
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isPassword(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private companion object {
        const val TAG = "WhisperKeyboard"
        const val WINDOW_VISIBLE_SETTLE_MS = 180L
    }
}
