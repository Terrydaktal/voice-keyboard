package io.github.terrydaktal.whisperkeyboard

import android.view.inputmethod.InputConnection

/**
 * Character and word-boundary deletion helpers for the keyboard's Backspace key.
 */
internal object BackspaceDeletion {
    private const val LOOKBACK_CHARS = 128

    fun deleteChar(editor: InputConnection?): Boolean {
        if (editor == null) return false
        val selected = editor.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            return editor.commitText("", 1)
        }
        val before = editor.getTextBeforeCursor(2, 0)
        val count = if (before == null) 1 else singleCharDeleteCount(before)
        if (count <= 0) return false
        return editor.deleteSurroundingText(count, 0)
    }

    fun deleteWord(editor: InputConnection?): Boolean {
        if (editor == null) return false
        val selected = editor.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            return editor.commitText("", 1)
        }
        val before = editor.getTextBeforeCursor(LOOKBACK_CHARS, 0)
        val count = if (before == null) 1 else wordDeleteCount(before)
        if (count <= 0) return false
        return editor.deleteSurroundingText(count, 0)
    }

    /**
     * Returns the number of UTF-16 code units to delete for a single-character backspace,
     * keeping surrogate pairs (e.g. emoji) intact.
     */
    internal fun singleCharDeleteCount(beforeCursor: CharSequence): Int {
        val len = beforeCursor.length
        if (len == 0) return 0
        if (len >= 2 &&
            Character.isLowSurrogate(beforeCursor[len - 1]) &&
            Character.isHighSurrogate(beforeCursor[len - 2])
        ) {
            return 2
        }
        return 1
    }

    /**
     * Returns the number of UTF-16 code units to delete for one SwiftKey-style word step:
     * - A trailing newline is deleted on its own without jumping into the previous line.
     * - Otherwise consumes trailing horizontal spaces/tabs, then the contiguous run of
     *   non-whitespace characters before them (stopping at whitespace or a newline).
     */
    internal fun wordDeleteCount(beforeCursor: CharSequence): Int {
        val len = beforeCursor.length
        if (len == 0) return 0

        var index = len
        if (beforeCursor[index - 1] == '\n') {
            return if (index >= 2 && beforeCursor[index - 2] == '\r') 2 else 1
        }

        // Consume trailing horizontal whitespace (spaces/tabs) on the current line.
        while (index > 0 && isHorizontalSpace(beforeCursor[index - 1])) {
            index--
        }

        // If the line only had spaces after a newline, stop before the newline.
        if (index > 0 && beforeCursor[index - 1] == '\n') {
            return len - index
        }

        // Consume the contiguous non-whitespace word (including attached punctuation/apostrophes).
        while (index > 0 && !Character.isWhitespace(beforeCursor[index - 1])) {
            index--
        }

        return len - index
    }

    private fun isHorizontalSpace(ch: Char): Boolean =
        ch != '\n' && ch != '\r' && Character.isWhitespace(ch)
}
