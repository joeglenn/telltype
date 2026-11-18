package net.jbglenn.telltype.ui.screens.setup

import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.ui.screens.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressParsingTest {
    @Test
    fun withoutASchemeAnAddressIsHttpsOnTheGatePort() {
        assertEquals(ServerEndpoint("192.0.2.10", 8170), parseEndpoint("192.0.2.10"))
        assertEquals(ServerEndpoint("192.0.2.10", 9000), parseEndpoint(" 192.0.2.10:9000 "))
        assertEquals(ServerEndpoint("server.example", 8170), parseEndpoint("server.example/"))
        assertEquals(ServerEndpoint("fd00::1", 8170), parseEndpoint("[fd00::1]:8170"))
        assertEquals(ServerEndpoint("fd00::1", 8170), parseEndpoint("fd00::1"))
    }

    @Test
    fun aSchemeKeepsItsOwnDefaultPortAndAV1PathIsDropped() {
        assertEquals(ServerEndpoint("server.example", 443), parseEndpoint("https://server.example"))
        assertEquals(ServerEndpoint("192.0.2.10", 80, Scheme.HTTP), parseEndpoint("http://192.0.2.10"))
        assertEquals(ServerEndpoint("192.168.1.20", 8000, Scheme.HTTP), parseEndpoint("HTTP://192.168.1.20:8000/v1/"))
        assertEquals(ServerEndpoint("fd00::2", 11434, Scheme.HTTP), parseEndpoint("http://[fd00::2]:11434/v1"))
        assertNull("another path is not an address", parseEndpoint("http://192.168.1.20:8000/api/chat"))
        assertNull(parseEndpoint("ftp://192.168.1.20"))
    }

    @Test
    fun plainHttpIsAcceptedOnlyForPrivateAddresses() {
        val private = listOf(
            "http://10.0.2.2:18200",
            "http://172.20.1.5:8000",
            "http://192.168.1.20:8000",
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "http://169.254.10.1",
            "http://100.64.0.20:8000",
            "http://[fd12:3456::1]:8000",
            "http://[fe80::1]:8000",
            "http://myserver.local:8000",
        )
        private.forEach { assertEquals(it, Scheme.HTTP, (checkAddress(it) as AddressCheck.Valid).endpoint.scheme) }
        val public = listOf("http://203.0.113.5", "http://server.example", "http://100.128.0.1", "http://[2001:db8::1]")
        public.forEach { assertEquals(it, AddressCheck.Problem(R.string.setup_error_address_http), checkAddress(it)) }
    }

    @Test
    fun theDisplayTextParsesBackToTheSameAddress() {
        listOf(
            ServerEndpoint("192.0.2.10", 8170),
            ServerEndpoint("fd00::1", 8443),
            ServerEndpoint("192.168.1.20", 8000, Scheme.HTTP),
            ServerEndpoint("server.example", 443),
        ).forEach { assertEquals(it, parseEndpoint(it.displayText())) }
        assertEquals("http://192.168.1.20:8000", ServerEndpoint("192.168.1.20", 8000, Scheme.HTTP).displayText())
    }
}
