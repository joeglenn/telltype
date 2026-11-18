package net.jbglenn.telltype.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** D60: the scheme, the text model address with its certificate, and the chosen models survive a round trip. */
class ServerSettingsCodecTest {
    private fun roundTrip(settings: Settings): Pair<Settings, Map<String, Any?>> {
        val preferences = mutablePreferencesOf()
        SettingsCodec.encode(settings, preferences)
        val values = preferences.asMap().mapKeys { it.key.name }
        return SettingsCodec.decode(values, settings.server.key).first to values
    }

    @Test
    fun `a stock setup with two plain HTTP addresses and models round-trips`() {
        val server = ServerConfig(
            address = ServerEndpoint("192.168.1.20", 18201, Scheme.HTTP),
            key = null,
            certificate = null,
            textAddress = ServerEndpoint("fd00::2", 8443),
            textCertificate = CertificateFingerprint("c".repeat(64)),
            models = ServerModels(speech = "whisper-1", cleanup = "qwen3.5-4b", command = "gemma4:12b-qat"),
        )
        val settings = Settings.Defaults.copy(server = server)

        val (decoded, values) = roundTrip(settings)

        assertEquals(server, decoded.server)
        assertEquals("""{"host":"192.168.1.20","port":18201,"scheme":"http"}""", values["server.address"])
        assertEquals("""{"host":"fd00::2","port":8443}""", values["server.text_address"])
    }

    @Test
    fun `an address stored before D60 reads as HTTPS and nothing else is set`() {
        val (settings, problems) = SettingsCodec.decode(
            mapOf("server.address" to """{"host":"192.0.2.10","port":8170}"""),
            null,
        )
        assertEquals(ServerConfig(ServerEndpoint("192.0.2.10", 8170), null, null), settings.server)
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `an unknown scheme is reported by name and dropped`() {
        val (settings, problems) = SettingsCodec.decode(
            mapOf("server.text_address" to """{"host":"192.0.2.10","port":80,"scheme":"gopher"}"""),
            null,
        )
        assertEquals(null, settings.server.textAddress)
        assertEquals(listOf("server.text_address"), problems)
    }
}
