package net.jbglenn.telltype.network.http

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.settings.BackendHealth
import net.jbglenn.telltype.core.model.settings.ServerStatus
import net.jbglenn.telltype.domain.cleanup.ChatMessage
import net.jbglenn.telltype.domain.cleanup.ChatPrompt
import net.jbglenn.telltype.domain.cleanup.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GateBodiesTest {
    @Test
    fun `server timing entries are parsed and bad ones skipped`() {
        val parsed = parseServerTiming(
            listOf(
                "decode;dur=44.0, queue;dur=0.0, stt;dur=125.4",
                "gate;desc=\"total\";dur=172.1",
                "x;dur=-1, y, ;dur=3",
            ),
        )
        assertEquals(
            listOf(
                ServerTiming("decode", 44.milliseconds),
                ServerTiming("queue", 0.milliseconds),
                ServerTiming("stt", 125.4.milliseconds),
                ServerTiming("gate", 172.1.milliseconds),
            ),
            parsed,
        )
        assertEquals(emptyList<ServerTiming>(), parseServerTiming(emptyList()))
    }

    @Test
    fun `retry after is read in seconds only`() {
        assertEquals(5.seconds, parseRetryAfter("5"))
        assertEquals(0.seconds, parseRetryAfter(" 0 "))
        assertNull(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(parseRetryAfter("-3"))
        assertNull(parseRetryAfter(null))
    }

    @Test
    fun `transcription bodies`() {
        val full = parseTranscription("""{"text":"hello there","duration":9.8,"language":"en"}""")!!
        assertEquals("hello there", full.text)
        assertEquals(9.8, full.durationSeconds!!, 0.0)
        assertNull(parseTranscription("""{"text":"x","duration":"long"}""")!!.durationSeconds)
        assertEquals("", parseTranscription("""{"text":""}""")!!.text)
        for (bad in listOf(
            """{"duration":1}""",
            """{"text":null}""",
            """{"text":3}""",
            """["text"]""",
            """{"text":"cut""",
            "",
        )) {
            assertNull("malformed: $bad", parseTranscription(bad))
        }
    }

    @Test
    fun `chat bodies`() {
        val reply = parseChatReply(
            """{"choices":[{"message":{"role":"assistant","content":"Hi."},"finish_reason":"stop"}]}""",
        )!!
        assertEquals("Hi.", reply.content)
        assertEquals("stop", reply.finishReason)
        val nullContent = parseChatReply("""{"choices":[{"message":{"content":null}}]}""")!!
        assertNull(nullContent.content)
        assertNull(nullContent.finishReason)
        for (bad in listOf(
            """{"choices":[]}""",
            """{"choices":[{}]}""",
            """{"choices":[{"message":{"content":4}}]}""",
            "{",
        )) {
            assertNull("malformed: $bad", parseChatReply(bad))
        }
    }

    @Test
    fun `health bodies`() {
        val body = """{"status":"degraded","version":"0.1.0",
            "stt":{"ready":true,"model":"parakeet-unified-en-0.6b-int8","engine":"sherpa-onnx"},
            "cleanup":{"ready":false,"model":"gemma-4-e4b-it-qat-q4_0"},
            "assistant":{"ready":true,"model":"qwen3.5-4b-q4_k_m"}}"""
        val health = parseHealth(body)!!
        assertEquals(ServerStatus.DEGRADED, health.status)
        assertEquals("0.1.0", health.version)
        assertEquals(BackendHealth(true, "parakeet-unified-en-0.6b-int8", "sherpa-onnx"), health.stt)
        assertEquals(BackendHealth(false, "gemma-4-e4b-it-qat-q4_0", null), health.cleanup)
        assertNull(parseHealth(body.replace("degraded", "sleepy")))
        assertNull(parseHealth(body.replace("\"ready\":true", "\"ready\":\"yes\"")))
        assertNull(parseHealth("""{"status":"ok"}"""))
    }

    @Test
    fun `error codes are read from gate error bodies only`() {
        assertEquals("busy", parseErrorCode("""{"error":{"code":"busy","message":"x"}}"""))
        assertNull(parseErrorCode("""{"error":"busy"}"""))
        assertNull(parseErrorCode("<html>502</html>"))
    }

    @Test
    fun `chat request carries exactly the request fields`() {
        val prompt = ChatPrompt(
            model = "cleanup",
            messages = listOf(ChatMessage(ChatRole.SYSTEM, "sys"), ChatMessage(ChatRole.USER, "say \"hi\"")),
            temperature = 0.1,
            maxTokens = 64,
            enableThinking = false,
        )
        assertEquals(
            """{"model":"cleanup","messages":[{"role":"system","content":"sys"},""" +
                """{"role":"user","content":"say \"hi\""}],"stream":false,"temperature":0.1,"max_tokens":64,""" +
                """"chat_template_kwargs":{"enable_thinking":false}}""",
            chatRequestJson(prompt),
        )
    }
}
