package io.github.terrydaktal.whisperkeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutStoreTest {
    @Test
    fun returnKeyboardSelectionRespectsPreferenceCallerAndFallbacks() {
        val gboard = "com.google.android.inputmethod.latin/.LatinIME"
        val swiftKey = "com.touchtype.swiftkey/com.touchtype.KeyboardService"
        val heli = "helium314.keyboard/.latin.LatinIME"
        val candidates = listOf(gboard, swiftKey, heli)
        val packages = mapOf(
            gboard to "com.google.android.inputmethod.latin",
            swiftKey to "com.touchtype.swiftkey",
            heli to "helium314.keyboard",
        )

        assertEquals(
            heli,
            KeyboardLayoutStore.selectReturnImeId(candidates, packages, preferredImeId = heli, lastCallerImeId = gboard),
        )
        assertEquals(
            gboard,
            KeyboardLayoutStore.selectReturnImeId(candidates, packages, preferredImeId = null, lastCallerImeId = gboard),
        )
        assertEquals(
            gboard,
            KeyboardLayoutStore.selectReturnImeId(candidates, packages, preferredImeId = "removed/.Ime", lastCallerImeId = gboard),
        )
        assertEquals(
            swiftKey,
            KeyboardLayoutStore.selectReturnImeId(candidates, packages, preferredImeId = null, lastCallerImeId = null),
        )
        assertEquals(
            gboard,
            KeyboardLayoutStore.selectReturnImeId(listOf(gboard, heli), packages, preferredImeId = null, lastCallerImeId = null),
        )
    }
}
