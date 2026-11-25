package io.github.terrydaktal.whisperkeyboard

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Resolves and dispatches Android IME Enter key behavior matching SwiftKey:
 * runs explicit IME actions (Send, Search, Go, Next, Done, Previous) when enabled,
 * inserts a newline in multi-line editors, and sends KEYCODE_ENTER otherwise.
 */
internal object EnterAction {
    internal sealed class Behavior {
        data class EditorAction(val actionId: Int) : Behavior()
        data object InsertNewline : Behavior()
        data object SendEnterKey : Behavior()
    }

    fun perform(editor: InputConnection?, info: EditorInfo?): Boolean {
        if (editor == null) return false
        val behavior = if (info == null) {
            Behavior.SendEnterKey
        } else {
            resolve(info.imeOptions, info.inputType)
        }
        return when (behavior) {
            is Behavior.EditorAction -> editor.performEditorAction(behavior.actionId)
            Behavior.InsertNewline -> editor.commitText("\n", 1)
            Behavior.SendEnterKey -> {
                val down = editor.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                val up = editor.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                down || up
            }
        }
    }

    fun labelRes(info: EditorInfo?): Int {
        if (info == null) return R.string.enter_description
        return when (val behavior = resolve(info.imeOptions, info.inputType)) {
            is Behavior.EditorAction -> when (behavior.actionId) {
                EditorInfo.IME_ACTION_SEND -> R.string.enter_send
                EditorInfo.IME_ACTION_SEARCH -> R.string.enter_search
                EditorInfo.IME_ACTION_GO -> R.string.enter_go
                EditorInfo.IME_ACTION_NEXT -> R.string.enter_next
                EditorInfo.IME_ACTION_DONE -> R.string.enter_done
                else -> R.string.enter_description
            }
            Behavior.InsertNewline, Behavior.SendEnterKey -> R.string.enter_description
        }
    }

    internal fun resolve(imeOptions: Int, inputType: Int): Behavior {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (!noEnterAction && action in ACTIONABLE_IME_ACTIONS) {
            return Behavior.EditorAction(action)
        }
        return if (isMultiLine) Behavior.InsertNewline else Behavior.SendEnterKey
    }

    private val ACTIONABLE_IME_ACTIONS = setOf(
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_NEXT,
        EditorInfo.IME_ACTION_DONE,
        EditorInfo.IME_ACTION_PREVIOUS,
    )
}
