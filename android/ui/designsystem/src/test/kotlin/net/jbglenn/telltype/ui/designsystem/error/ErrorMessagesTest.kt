package net.jbglenn.telltype.ui.designsystem.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorMessagesTest {
    @Test
    fun everyKnownErrorHasItsOwnCodeAndMessage() {
        assertEquals("one instance per DictationError case", 26, KnownErrors.size)
        assertEquals(KnownErrors.size, KnownErrors.map { it.code }.toSet().size)
        assertEquals(KnownErrors.size, KnownErrors.map { it.messageRes() }.toSet().size)
    }

    @Test
    fun storedCodesMapBackToTheirMessage() {
        KnownErrors.forEach { error ->
            assertEquals(error.code, error.messageRes(), errorMessageForCode(error.code))
        }
        assertNull(errorMessageForCode("written_by_a_newer_version"))
    }
}
