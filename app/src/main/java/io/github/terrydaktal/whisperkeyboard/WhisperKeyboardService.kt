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
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import io.github.terrydaktal.whisperkeyboard.WhisperRecordingService.Phase
import java.io.FileDescriptor
import java.io.PrintWriter

class WhisperKeyboardService : InputMethodService() {
    internal data class EditorIdentity(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private var panel: MicrophonePanel? = null
    private var viewActive = false
    private var viewGeneration = 0L
    private var autoStartOnOpen = true
    private var passwordField = false
    private var submittedFrom: InputConnection? = null
    private var submittedEditor: EditorIdentity? = null
    private val layoutPreferences by lazy { KeyboardLayoutStore.preferences(this) }
    private val dictationPreferences by lazy { DictationSettings.preferences(this) }
    private val onLayoutChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        panel?.keyboardHeight = KeyboardLayoutStore.keyboardHeight(this)
        panel?.updateReturnKeyboard(KeyboardLayoutStore.returnKeyboardLabel(this))
    }
    private val onDictationSettingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshBackendButton()
    }

    private val onStateChanged: (WhisperRecordingService.Snapshot) -> Unit = { state ->
        if (state.phase == Phase.STARTING) autoStartOnOpen = false
        if (state.phase == Phase.STARTING || state.phase == Phase.FINISHED) {
            submittedFrom = null
            submittedEditor = null
        }
        render(state)
        if (state.phase == Phase.RESULT) insertTranscript(showError = false)
    }

    private val onAudioLevel: (Float) -> Unit = { level ->
        if (viewActive) panel?.level(level)
    }

    override fun onCreate() {
        super.onCreate()
        DictationDiagnostics.init(this)
        WhisperRecordingService.refreshPendingCount(this)
        WhisperRecordingService.listeners.add(onStateChanged)
        WhisperRecordingService.levelListeners.add(onAudioLevel)
        layoutPreferences.registerOnSharedPreferenceChangeListener(onLayoutChanged)
        dictationPreferences.registerOnSharedPreferenceChangeListener(onDictationSettingsChanged)
        DictationDiagnostics.record(category = "ime", event = "ime_create")
    }

    override fun dump(fd: FileDescriptor, fout: PrintWriter, args: Array<out String>?) {
        super.dump(fd, fout, args)
        DictationDiagnostics.dumpToWriter(fout, this, "WhisperKeyboardService")
    }

    override fun onCreateInputView(): View = MicrophonePanel(
        this,
        ::handleMic,
        ::returnToConfiguredKeyboard,
        ::showBackendMenu,
        ::deleteCharacter,
        ::deleteWord,
        ::pressEnter,
        ::handlePendingTap,
        ::showPendingMenu,
    ).also {
        panel = it
        it.updateReturnKeyboard(KeyboardLayoutStore.returnKeyboardLabel(this))
        it.updateEnterAction(EnterAction.labelRes(currentInputEditorInfo))
        refreshBackendButton()
        render(WhisperRecordingService.snapshot)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val opening = !viewActive || !restarting
        viewActive = true
        passwordField = isPassword(info.inputType)
        syncImeDiagnostics(info)
        DictationDiagnostics.record(
            category = "ime",
            event = "start_input_view",
            opId = WhisperRecordingService.snapshot.opId,
            fields = mapOf(
                "restarting" to restarting,
                "opening" to opening,
                "editorPkg" to info.packageName.orEmpty(),
                "fieldId" to info.fieldId,
                "password" to passwordField,
                "hasIc" to (currentInputConnection != null),
                "phase" to WhisperRecordingService.snapshot.phase.name,
            ),
        )
        WhisperRecordingService.setKeyboardVisible(this, true)
        panel?.keyboardHeight = KeyboardLayoutStore.keyboardHeight(this)
        panel?.updateReturnKeyboard(KeyboardLayoutStore.returnKeyboardLabel(this))
        panel?.updateEnterAction(EnterAction.labelRes(info))
        refreshBackendButton()
        render(WhisperRecordingService.snapshot)
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
        DictationDiagnostics.record(
            category = "ime",
            event = "finish_input_view",
            opId = WhisperRecordingService.snapshot.opId,
            fields = mapOf(
                "finishingInput" to finishingInput,
                "phase" to WhisperRecordingService.snapshot.phase.name,
            ),
        )
        closeView()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        DictationDiagnostics.record(
            category = "ime",
            event = "window_hidden",
            opId = WhisperRecordingService.snapshot.opId,
            fields = mapOf("phase" to WhisperRecordingService.snapshot.phase.name),
        )
        closeView()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        closeView()
        WhisperRecordingService.listeners.remove(onStateChanged)
        WhisperRecordingService.levelListeners.remove(onAudioLevel)
        layoutPreferences.unregisterOnSharedPreferenceChangeListener(onLayoutChanged)
        dictationPreferences.unregisterOnSharedPreferenceChangeListener(onDictationSettingsChanged)
        DictationDiagnostics.record(category = "ime", event = "ime_destroy")
        super.onDestroy()
    }

    private fun closeView() {
        viewActive = false
        viewGeneration++
        syncImeDiagnostics(currentInputEditorInfo)
        WhisperRecordingService.setKeyboardVisible(this, false)
    }

    private fun refreshBackendButton() {
        panel?.updateBackend(DictationSettings.backend(this))
    }

    private fun showBackendMenu(anchor: View) {
        val recording = WhisperRecordingService.snapshot.phase == Phase.RECORDING
        DictationSettings.showBackendMenu(anchor, isRecording = recording)
    }

    private fun handlePendingTap(anchor: View) {
        if (passwordField) return
        val count = WhisperRecordingService.refreshPendingCount(this)
        if (count <= 0) return
        if (count == 1) {
            captureSubmissionTarget()
            WhisperRecordingService.retryPending(this, all = false)
        } else {
            showPendingMenu(anchor)
        }
    }

    private fun showPendingMenu(anchor: View) {
        val count = WhisperRecordingService.refreshPendingCount(this)
        if (count <= 0) return
        PopupMenu(this, anchor, Gravity.START).apply {
            menu.add(0, MENU_RETRY_OLDEST, 0, R.string.pending_menu_retry_oldest)
            if (count > 1) {
                menu.add(0, MENU_RETRY_ALL, 1, getString(R.string.pending_menu_retry_all, count))
            }
            menu.add(0, MENU_DISCARD_OLDEST, 2, R.string.pending_menu_discard_oldest)
            if (count > 1) {
                menu.add(0, MENU_CLEAR_ALL, 3, getString(R.string.pending_menu_clear_all, count))
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RETRY_OLDEST -> {
                        if (!passwordField) {
                            captureSubmissionTarget()
                            WhisperRecordingService.retryPending(this@WhisperKeyboardService, all = false)
                        }
                    }
                    MENU_RETRY_ALL -> {
                        if (!passwordField) {
                            captureSubmissionTarget()
                            WhisperRecordingService.retryPending(this@WhisperKeyboardService, all = true)
                        }
                    }
                    MENU_DISCARD_OLDEST -> {
                        WhisperRecordingService.discardOldestPending(this@WhisperKeyboardService)
                        Toast.makeText(this@WhisperKeyboardService, R.string.pending_discarded, Toast.LENGTH_SHORT).show()
                    }
                    MENU_CLEAR_ALL -> {
                        val removed = WhisperRecordingService.clearAllPending(this@WhisperKeyboardService)
                        Toast.makeText(
                            this@WhisperKeyboardService,
                            getString(R.string.pending_cleared, removed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                true
            }
        }.show()
    }

    private fun deleteCharacter() {
        BackspaceDeletion.deleteChar(currentInputConnection)
    }

    private fun deleteWord() {
        BackspaceDeletion.deleteWord(currentInputConnection)
    }

    private fun pressEnter() {
        EnterAction.perform(currentInputConnection, currentInputEditorInfo)
    }

    private fun handleMic() {
        val phase = WhisperRecordingService.snapshot.phase
        DictationDiagnostics.record(
            category = "ime",
            event = "mic_tap",
            opId = WhisperRecordingService.snapshot.opId,
            fields = mapOf(
                "phase" to phase.name,
                "viewActive" to viewActive,
                "isShown" to isInputViewShown,
                "hasIc" to (currentInputConnection != null),
            ),
        )
        when (phase) {
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
                captureSubmissionTarget()
                WhisperRecordingService.stopFromKeyboard()
            }
            Phase.RESULT -> insertTranscript(showError = true)
            Phase.STARTING, Phase.TRANSCRIBING -> Unit
        }
    }

    private fun captureSubmissionTarget() {
        submittedFrom = currentInputConnection
        submittedEditor = currentEditorIdentity()
        syncImeDiagnostics(currentInputEditorInfo)
    }

    private fun currentEditorIdentity(): EditorIdentity? {
        val info = currentInputEditorInfo ?: return null
        return EditorIdentity(
            packageName = info.packageName.orEmpty(),
            fieldId = info.fieldId,
            inputType = info.inputType,
        )
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
        val snap = WhisperRecordingService.snapshot
        val opId = snap.opId
        val text = snap.transcript
        val editor = currentInputConnection
        val currentEd = currentEditorIdentity()
        val sameIc = submittedFrom == null || editor === submittedFrom
        val sameEditor = isSameSubmissionTarget(submittedEditor, currentEd, sameIc)

        val blockReason = when {
            !viewActive -> "view_inactive"
            !isInputViewShown -> "view_not_shown"
            passwordField -> "password_field"
            editor == null -> "no_input_connection"
            text == null -> "no_transcript"
            !showError && WhisperRecordingService.manualInsertRequired -> "manual_insert_required"
            !showError && !sameEditor -> "editor_changed"
            else -> null
        }

        if (blockReason != null) {
            DictationDiagnostics.counters.commitsBlocked.incrementAndGet()
            DictationDiagnostics.updateImeState {
                it.copy(
                    viewActive = viewActive,
                    isInputViewShown = isInputViewShown,
                    hasInputConnection = editor != null,
                    lastInsertDecision = "blocked:$blockReason",
                    lastInsertAtWallMs = System.currentTimeMillis(),
                )
            }
            DictationDiagnostics.record(
                category = "ime",
                event = "insert_blocked",
                opId = opId,
                level = DiagnosticFlightRecorder.LEVEL_WARN,
                fields = mapOf(
                    "reason" to blockReason,
                    "showError" to showError,
                    "viewActive" to viewActive,
                    "isShown" to isInputViewShown,
                    "hasIc" to (editor != null),
                    "sameIc" to sameIc,
                    "sameEditor" to sameEditor,
                    "manualReq" to WhisperRecordingService.manualInsertRequired,
                    "editorPkg" to (currentEd?.packageName ?: ""),
                    "submittedPkg" to (submittedEditor?.packageName ?: ""),
                    "transcriptLen" to (text?.length ?: 0),
                ),
            )
            DictationDiagnostics.persistSnapshot(this)
            if (blockReason == "editor_changed" && viewActive && isInputViewShown) {
                Toast.makeText(this, R.string.tap_insert_transcript, Toast.LENGTH_SHORT).show()
            }
            return
        }

        DictationDiagnostics.counters.commitsAttempted.incrementAndGet()
        val committed = editor!!.commitText(text!!, 1)
        if (committed) {
            DictationDiagnostics.counters.commitsSucceeded.incrementAndGet()
            DictationDiagnostics.updateImeState {
                it.copy(
                    lastInsertDecision = "committed",
                    lastInsertAtWallMs = System.currentTimeMillis(),
                )
            }
            DictationDiagnostics.record(
                category = "ime",
                event = "insert_committed",
                opId = opId,
                fields = mapOf(
                    "transcriptLen" to text.length,
                    "sameIc" to sameIc,
                    "sameEditor" to sameEditor,
                    "editorPkg" to (currentEd?.packageName ?: ""),
                    "showError" to showError,
                ),
            )
            DictationDiagnostics.persistSnapshot(this)
            WhisperRecordingService.transcriptInserted()
        } else {
            DictationDiagnostics.counters.commitsFailed.incrementAndGet()
            DictationDiagnostics.updateImeState {
                it.copy(
                    lastInsertDecision = "commit_returned_false",
                    lastInsertAtWallMs = System.currentTimeMillis(),
                )
            }
            DictationDiagnostics.record(
                category = "ime",
                event = "insert_failed",
                opId = opId,
                level = DiagnosticFlightRecorder.LEVEL_ERROR,
                fields = mapOf(
                    "transcriptLen" to text.length,
                    "sameIc" to sameIc,
                    "sameEditor" to sameEditor,
                    "editorPkg" to (currentEd?.packageName ?: ""),
                    "showError" to showError,
                ),
            )
            DictationDiagnostics.persistSnapshot(this)
            Toast.makeText(this, R.string.insert_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncImeDiagnostics(info: EditorInfo?) {
        DictationDiagnostics.updateImeState {
            it.copy(
                viewActive = viewActive,
                isInputViewShown = isInputViewShown,
                passwordField = passwordField,
                hasInputConnection = currentInputConnection != null,
                hasSubmittedConnection = submittedFrom != null,
                editorPackage = info?.packageName.orEmpty(),
                editorInputType = info?.inputType ?: 0,
            )
        }
    }

    private fun render(state: WhisperRecordingService.Snapshot) {
        val phase = state.phase
        val label = when (phase) {
            Phase.RECORDING -> R.string.stop_recording
            Phase.STARTING -> R.string.starting_recording
            Phase.TRANSCRIBING -> R.string.transcribing
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
        val pendingEnabled = !busy && phase != Phase.RECORDING && !passwordField
        panel?.updatePending(state.pendingCount, pendingEnabled)
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

    internal companion object {
        const val TAG = "WhisperKeyboard"
        const val WINDOW_VISIBLE_SETTLE_MS = 180L
        const val MENU_RETRY_OLDEST = 1
        const val MENU_RETRY_ALL = 2
        const val MENU_DISCARD_OLDEST = 3
        const val MENU_CLEAR_ALL = 4

        /**
         * Determines whether the current editor matches the editor where Stop was pressed.
         * Allows Android/host apps to refresh the InputConnection wrapper object during long
         * recordings as long as the package, field ID, and input type are unchanged.
         */
        internal fun isSameSubmissionTarget(
            submitted: EditorIdentity?,
            current: EditorIdentity?,
            sameConnectionReference: Boolean,
        ): Boolean {
            if (submitted == null) return true
            if (sameConnectionReference) return true
            if (current == null) return false
            return submitted == current
        }
    }
}
