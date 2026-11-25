package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast

internal enum class DictationBackend(val menuLabel: Int, val buttonLabel: Int) {
    RAMBLER(R.string.backend_rambler_menu, R.string.backend_rambler_button),
    WHISPER(R.string.backend_whisper_menu, R.string.backend_whisper_button),
}

internal object DictationSettings {
    private const val PREFS = "whisper_keyboard"
    private const val BACKEND_KEY = "dictation_backend"
    private const val LOCALE_KEY = "rambler_locale"
    private const val BACKENDS_GROUP = 1

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun availableBackends(context: Context): List<DictationBackend> =
        if (WhisperEndpoint.isConfigured(context)) {
            listOf(DictationBackend.RAMBLER, DictationBackend.WHISPER)
        } else {
            listOf(DictationBackend.RAMBLER)
        }

    fun backend(context: Context): DictationBackend {
        if (!WhisperEndpoint.isConfigured(context)) return DictationBackend.RAMBLER
        val saved = preferences(context).getString(BACKEND_KEY, null)
        return DictationBackend.entries.firstOrNull { it.name == saved } ?: DictationBackend.RAMBLER
    }

    fun setBackend(context: Context, backend: DictationBackend) {
        preferences(context).edit().putString(BACKEND_KEY, backend.name).apply()
    }

    fun locale(context: Context): String {
        val raw = preferences(context).getString(LOCALE_KEY, null)?.trim()
        return if (raw.isNullOrEmpty()) RamblerS3Proto.DEFAULT_LOCALE else raw
    }

    fun setLocale(context: Context, locale: String): String {
        val normalized = locale.trim().ifEmpty { RamblerS3Proto.DEFAULT_LOCALE }
        preferences(context).edit().putString(LOCALE_KEY, normalized).apply()
        return normalized
    }

    fun showBackendMenu(anchor: View, isRecording: Boolean) {
        val context = anchor.context
        val available = availableBackends(context)
        val selected = backend(context)

        PopupMenu(context, anchor, Gravity.END).apply {
            for ((index, candidate) in available.withIndex()) {
                menu.add(BACKENDS_GROUP, index, index, candidate.menuLabel)
            }
            menu.setGroupCheckable(BACKENDS_GROUP, true, true)
            val checkedIndex = available.indexOf(selected).coerceAtLeast(0)
            menu.findItem(checkedIndex).isChecked = true
            setOnMenuItemClickListener { item ->
                val chosen = available[item.itemId]
                setBackend(context, chosen)
                if (available.size > 1) {
                    val messageRes = if (isRecording) {
                        R.string.backend_next_recording
                    } else {
                        R.string.backend_switched
                    }
                    Toast.makeText(
                        context,
                        context.getString(messageRes, context.getString(chosen.menuLabel)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                true
            }
        }.show()
    }
}
