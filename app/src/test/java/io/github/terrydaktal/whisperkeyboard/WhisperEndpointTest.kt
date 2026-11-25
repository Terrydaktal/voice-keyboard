package io.github.terrydaktal.whisperkeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperEndpointTest {
    @Test
    fun parseRejectsBlankAndNormalizesHostUrl() {
        assertEquals(null, WhisperEndpoint.parse(""))
        assertEquals(null, WhisperEndpoint.parse("   "))
        assertEquals(
            "http://10.0.0.5:5001/transcribe_raw",
            WhisperEndpoint.parse("http://10.0.0.5:5001")?.toExternalForm(),
        )
        assertEquals(
            "https://example.com/custom",
            WhisperEndpoint.parse("https://example.com/custom")?.toExternalForm(),
        )
    }
}
