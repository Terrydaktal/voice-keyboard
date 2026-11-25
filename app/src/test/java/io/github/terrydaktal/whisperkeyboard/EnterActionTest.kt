package io.github.terrydaktal.whisperkeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterActionTest {
    @Test
    fun resolvesExplicitEditorActionsWhenAllowed() {
        assertEquals(
            EnterAction.Behavior.EditorAction(EditorInfo.IME_ACTION_SEND),
            EnterAction.resolve(EditorInfo.IME_ACTION_SEND, InputType.TYPE_CLASS_TEXT),
        )
        assertEquals(
            EnterAction.Behavior.EditorAction(EditorInfo.IME_ACTION_SEARCH),
            EnterAction.resolve(EditorInfo.IME_ACTION_SEARCH, InputType.TYPE_CLASS_TEXT),
        )
        assertEquals(
            EnterAction.Behavior.EditorAction(EditorInfo.IME_ACTION_DONE),
            EnterAction.resolve(EditorInfo.IME_ACTION_DONE, InputType.TYPE_CLASS_TEXT),
        )
    }

    @Test
    fun insertsNewlineForMultiLineEditorsWithNoEnterActionOrUnspecified() {
        val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(
            EnterAction.Behavior.InsertNewline,
            EnterAction.resolve(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION, multiLine),
        )
        assertEquals(
            EnterAction.Behavior.InsertNewline,
            EnterAction.resolve(EditorInfo.IME_ACTION_NONE, multiLine),
        )
        assertEquals(
            EnterAction.Behavior.InsertNewline,
            EnterAction.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, multiLine),
        )
    }

    @Test
    fun sendsEnterKeyForSingleLineEditorsWithoutAction() {
        assertEquals(
            EnterAction.Behavior.SendEnterKey,
            EnterAction.resolve(EditorInfo.IME_ACTION_NONE, InputType.TYPE_CLASS_TEXT),
        )
        assertEquals(
            EnterAction.Behavior.SendEnterKey,
            EnterAction.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, InputType.TYPE_CLASS_TEXT),
        )
    }
}
