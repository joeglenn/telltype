package net.jbglenn.telltype.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LoggerTest {
    private class ListLogger(private val minimum: LogLevel) : Logger {
        val lines = mutableListOf<String>()
        var evaluated = 0

        override fun isEnabled(level: LogLevel): Boolean = level >= minimum

        override fun log(level: LogLevel, tag: LogTag, throwable: Throwable?, message: () -> String) {
            if (!isEnabled(level)) return
            evaluated++
            lines += "$level ${tag.name} ${message()}${throwable?.let { " (${it.message})" }.orEmpty()}"
        }
    }

    private val tag = LogTag("Session")

    @Test
    fun `redacted text prints only its length`() {
        val text = Redacted("call mum at six")

        assertEquals("cleaned ‹redacted, 15 chars›", "cleaned $text")
        assertFalse("$text".contains("mum"))
        assertEquals("call mum at six", text.unredacted())
    }

    @Test
    fun `helpers write at their level and skip disabled levels without evaluating the message`() {
        val logger = ListLogger(minimum = LogLevel.INFO)

        logger.debug(tag) { "never built" }
        logger.info(tag) { "started" }
        logger.warn(tag, null) { "degraded" }
        logger.error(tag, IllegalStateException("cause")) { "failed" }

        assertEquals(
            listOf("INFO Session started", "WARN Session degraded", "ERROR Session failed (cause)"),
            logger.lines,
        )
        assertEquals(3, logger.evaluated)
    }

    @Test
    fun `log tags are 1 to 23 characters`() {
        assertTrue(LogTag("A".repeat(23)).name.length == 23)
        assertThrows<IllegalArgumentException> { LogTag("") }
        assertThrows<IllegalArgumentException> { LogTag("A".repeat(24)) }
    }
}
