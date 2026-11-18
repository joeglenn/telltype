package net.jbglenn.telltype.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OutcomeTest {
    private val ok: Outcome<Int, String> = Outcome.Ok(2)
    private val err: Outcome<Int, String> = Outcome.Err("boom")

    @Test
    fun `map transforms only a success`() {
        assertEquals(Outcome.Ok(4), ok.map { it * 2 })
        assertEquals(err, err.map { it * 2 })
    }

    @Test
    fun `mapError transforms only an error`() {
        assertEquals(ok, ok.mapError { it.length })
        assertEquals(Outcome.Err(4), err.mapError { it.length })
    }

    @Test
    fun `flatMap chains a second step only after a success`() {
        assertEquals(Outcome.Err("odd"), ok.flatMap { if (it % 2 == 0) Outcome.Err("odd") else Outcome.Ok(it) })
        assertEquals(Outcome.Ok(3), ok.flatMap { Outcome.Ok(it + 1) })
        assertEquals(err, err.flatMap { Outcome.Ok(it + 1) })
    }

    @Test
    fun `fold collapses both cases`() {
        assertEquals("ok 2", ok.fold(onOk = { "ok $it" }, onErr = { "err $it" }))
        assertEquals("err boom", err.fold(onOk = { "ok $it" }, onErr = { "err $it" }))
    }

    @Test
    fun `getOrNull drops the error`() {
        assertEquals(2, ok.getOrNull())
        assertNull(err.getOrNull())
    }
}
