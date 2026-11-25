package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.inputmethod.InputMethodInfo

/**
 * Stores keyboard height measurements, the most recent keyboard that launched
 * Voice Keyboard, and the user's preferred return keyboard.
 */
internal object KeyboardLayoutStore {
    private const val PREFS = "whisper_keyboard_layout"
    private const val LAST_CALLER_IME_KEY = "last_caller_ime"
    private const val LEGACY_SWIFTKEY_IME_KEY = "swiftkey_ime"
    private const val PREFERRED_RETURN_IME_KEY = "preferred_return_ime"
    private val SWIFTKEY_PACKAGES = setOf("com.touchtype.swiftkey", "com.touchtype.swiftkey.beta")

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveCallerKeyboardState(context: Context, callerIme: String, heightPx: Int) {
        preferences(context).edit().apply {
            putString(LAST_CALLER_IME_KEY, callerIme)
            if (heightPx > 0) putInt(heightKey(context), heightPx)
        }.apply()
    }

    fun keyboardHeight(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fallback = ((if (landscape) 202 else 296) * metrics.density).toInt()
        return preferences(context).getInt(heightKey(context), fallback)
    }

    /** Returns the user-selected return IME ID, or `null` for automatic mode. */
    fun preferredReturnIme(context: Context): String? =
        preferences(context).getString(PREFERRED_RETURN_IME_KEY, null)?.takeIf { it.isNotBlank() }

    /** Saves an explicit return keyboard IME ID, or `null` for automatic mode. */
    fun setPreferredReturnIme(context: Context, imeId: String?) {
        preferences(context).edit().apply {
            if (imeId.isNullOrBlank()) {
                remove(PREFERRED_RETURN_IME_KEY)
            } else {
                putString(PREFERRED_RETURN_IME_KEY, imeId)
            }
        }.apply()
    }

    fun lastCallerIme(context: Context): String? {
        val prefs = preferences(context)
        return prefs.getString(LAST_CALLER_IME_KEY, null)
            ?: prefs.getString(LEGACY_SWIFTKEY_IME_KEY, null)
    }

    /** Lists all enabled keyboards other than Voice Keyboard itself. */
    fun availableReturnKeyboards(context: Context): List<InputMethodInfo> =
        KeyboardSwitch.enabled(context).filter { it.packageName != context.packageName }

    /**
     * Resolves which keyboard the back button should switch to:
     * 1. Explicitly configured return keyboard (if still enabled)
     * 2. Last keyboard that launched Voice Keyboard (if still enabled)
     * 3. SwiftKey (if enabled, for smooth migration)
     * 4. First enabled non-Voice keyboard
     */
    fun resolveReturnKeyboard(context: Context): InputMethodInfo? {
        val candidates = availableReturnKeyboards(context)
        if (candidates.isEmpty()) return null
        val selectedId = selectReturnImeId(
            candidateIds = candidates.map { it.id },
            candidatePackages = candidates.associate { it.id to it.packageName },
            preferredImeId = preferredReturnIme(context),
            lastCallerImeId = lastCallerIme(context),
        )
        return candidates.firstOrNull { it.id == selectedId }
    }

    fun keyboardLabel(context: Context, info: InputMethodInfo): String {
        val label = runCatching { info.loadLabel(context.packageManager)?.toString()?.trim() }
            .getOrNull()
        return if (!label.isNullOrEmpty()) label else info.packageName
    }

    fun returnKeyboardLabel(context: Context): String? =
        resolveReturnKeyboard(context)?.let { keyboardLabel(context, it) }

    internal fun selectReturnImeId(
        candidateIds: List<String>,
        candidatePackages: Map<String, String>,
        preferredImeId: String?,
        lastCallerImeId: String?,
    ): String? {
        if (candidateIds.isEmpty()) return null
        if (preferredImeId != null && preferredImeId in candidateIds) return preferredImeId
        if (lastCallerImeId != null && lastCallerImeId in candidateIds) return lastCallerImeId
        candidateIds.firstOrNull { candidatePackages[it] in SWIFTKEY_PACKAGES }?.let { return it }
        return candidateIds.first()
    }

    private fun heightKey(context: Context): String {
        val config = context.resources.configuration
        return "ime_height_${config.orientation}_${config.densityDpi}"
    }
}
