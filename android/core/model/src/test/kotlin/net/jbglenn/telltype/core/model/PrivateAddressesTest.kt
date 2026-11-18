package net.jbglenn.telltype.core.model

import net.jbglenn.telltype.core.model.settings.PrivateAddresses
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D60: plain HTTP only to private addresses. */
class PrivateAddressesTest {
    @Test
    fun `private ranges, loopback, link-local, CGNAT, ULA and local names are private`() {
        listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "127.0.0.1",
            "169.254.1.1", "100.64.0.1", "100.127.255.254", "::1", "0:0:0:0:0:0:0:1", "fd00::1", "fc00::",
            "fe80::1", "FE80::abcd", "::ffff:192.168.1.20", "localhost", "LOCALHOST.", "printer.local",
            "box.localhost",
        ).forEach { assertTrue(PrivateAddresses.isPrivateHost(it), it) }
    }

    @Test
    fun `public addresses, other names and malformed literals are not private`() {
        listOf(
            "8.8.8.8", "172.15.255.255", "172.32.0.1", "192.169.0.1", "100.63.255.255", "100.128.0.1",
            "11.0.0.1", "2001:db8::1", "fec0::1", "::ffff:8.8.8.8", "server.example", "local", "my.local.example",
            "192.168.1", "192.168.1.256", "fd00::1::2", "1:2:3:4:5:6:7:8:9", "",
        ).forEach { assertFalse(PrivateAddresses.isPrivateHost(it), it) }
    }

    @Test
    fun `raw addresses of both families`() {
        assertTrue(PrivateAddresses.isPrivateAddress(byteArrayOf(10, 0, 2, 2)))
        assertFalse(PrivateAddresses.isPrivateAddress(byteArrayOf(8, 8, 4, 4)))
        assertTrue(PrivateAddresses.isPrivateAddress(ByteArray(16).also { it[0] = 0xfd.toByte() }))
        assertFalse(PrivateAddresses.isPrivateAddress(ByteArray(5)))
    }
}
