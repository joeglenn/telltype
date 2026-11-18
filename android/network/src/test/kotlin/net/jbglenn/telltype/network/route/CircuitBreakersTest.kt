package net.jbglenn.telltype.network.route

import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.testing.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitBreakersTest {
    private val clock = FixedClock()
    private val breakers = CircuitBreakers(clock)
    private val home = ServerEndpoint("192.0.2.10", 8170)
    private val tailscale = ServerEndpoint("100.64.0.20", 8170)

    private fun failTimes(n: Int, endpoint: ServerEndpoint = home) = repeat(n) { breakers.recordFailure(endpoint) }

    @Test
    fun `two failures keep the breaker closed, the third opens it`() {
        failTimes(2)
        assertEquals(CircuitBreakers.Permit.NORMAL, breakers.acquire(home))
        breakers.recordFailure(home)
        assertTrue(breakers.isOpen(home))
        assertNull(breakers.acquire(home))
        assertEquals("breakers are per address", CircuitBreakers.Permit.NORMAL, breakers.acquire(tailscale))
    }

    @Test
    fun `reset closes every breaker and clears the counts`() {
        failTimes(3)
        failTimes(2, tailscale)

        breakers.reset()

        assertFalse(breakers.isOpen(home))
        assertEquals(CircuitBreakers.Permit.NORMAL, breakers.acquire(home))
        breakers.recordFailure(tailscale)
        assertFalse("the old count is gone", breakers.isOpen(tailscale))
    }

    @Test
    fun `a success resets the count`() {
        failTimes(2)
        breakers.recordSuccess(home)
        failTimes(2)
        assertEquals(CircuitBreakers.Permit.NORMAL, breakers.acquire(home))
    }

    @Test
    fun `after the cooldown exactly one probe is allowed`() {
        failTimes(3)
        clock.advanceBy(29.seconds)
        assertNull(breakers.acquire(home))
        clock.advanceBy(1.seconds)
        assertEquals(CircuitBreakers.Permit.PROBE, breakers.acquire(home))
        assertNull("only one probe at a time", breakers.acquire(home))
    }

    @Test
    fun `a successful probe closes the breaker`() {
        failTimes(3)
        clock.advanceBy(30.seconds)
        breakers.acquire(home)
        breakers.recordSuccess(home)
        assertFalse(breakers.isOpen(home))
        assertEquals(CircuitBreakers.Permit.NORMAL, breakers.acquire(home))
    }

    @Test
    fun `a failed probe opens it for another cooldown`() {
        failTimes(3)
        clock.advanceBy(30.seconds)
        breakers.acquire(home)
        breakers.recordFailure(home)
        clock.advanceBy(29.seconds)
        assertNull(breakers.acquire(home))
        clock.advanceBy(1.seconds)
        assertEquals(CircuitBreakers.Permit.PROBE, breakers.acquire(home))
    }

    @Test
    fun `a released probe lets the next call probe at once`() {
        failTimes(3)
        clock.advanceBy(30.seconds)
        val permit = breakers.acquire(home)!!
        breakers.release(home, permit)
        assertEquals(CircuitBreakers.Permit.PROBE, breakers.acquire(home))
    }

    @Test
    fun `releasing a normal permit or after a verdict changes nothing`() {
        breakers.release(home, CircuitBreakers.Permit.NORMAL)
        assertEquals(CircuitBreakers.Permit.NORMAL, breakers.acquire(home))
        failTimes(3)
        clock.advanceBy(30.seconds)
        val probe = breakers.acquire(home)!!
        breakers.recordFailure(home)
        breakers.release(home, probe)
        assertNull("the failed probe's cooldown stands", breakers.acquire(home))
    }
}
