package io.github.terrydaktal.whisperkeyboard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager

internal object KeyboardSwitch {
    fun selected(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    fun packageOf(imeId: String?): String? =
        imeId?.let(ComponentName::unflattenFromString)?.packageName

    fun enabled(context: Context): List<InputMethodInfo> =
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun microphoneAllowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun select(context: Context, imeId: String): Boolean {
        return try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                imeId,
            )
        } catch (error: SecurityException) {
            Log.w("VoiceKeyboard", "Android rejected the keyboard switch", error)
            false
        }
    }
}
