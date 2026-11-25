package io.github.terrydaktal.whisperkeyboard

import android.app.Activity
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast

/**
 * Receives a keyboard's voice-input intent (`ACTION_RECOGNIZE_SPEECH`), finishes without
 * displaying a window, and asks [SwiftKeyBridgeService] to switch to Voice Keyboard.
 */
class SpeechBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        setResult(RESULT_CANCELED)
        finish()

        if (intent.action != RecognizerIntent.ACTION_RECOGNIZE_SPEECH) return
        if (caller == null || caller == packageName) {
            Log.d("VoiceKeyboard", "Ignored a speech request without an external keyboard caller")
            return
        }
        val service = SwiftKeyBridgeService.connected
        if (service == null) {
            Toast.makeText(this, R.string.enable_accessibility, Toast.LENGTH_SHORT).show()
            return
        }
        service.startDictation(caller)
    }
}
