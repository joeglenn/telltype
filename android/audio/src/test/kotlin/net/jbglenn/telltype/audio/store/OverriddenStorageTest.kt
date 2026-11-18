package net.jbglenn.telltype.audio.store

import java.io.File
import java.io.IOException
import net.jbglenn.telltype.audio.wav.RecordingSink
import net.jbglenn.telltype.audio.wav.RecordingSinkFactory
import net.jbglenn.telltype.audio.wav.WavFileWriter
import net.jbglenn.telltype.core.model.audio.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OverriddenStorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class Switch(var full: Boolean = false, var limit: Long? = null) : StorageOverride {
        override fun full(): Boolean = full

        override fun bytesBeforeFull(): Long? = limit
    }

    private val format = AudioFormat.Dictation

    @Test
    fun `a full disk reports no free space, otherwise the real figure`() {
        val switch = Switch()
        val space = OverriddenStorageSpace({ 123L }, switch)

        assertEquals(123L, space.usableBytes(folder.root))
        switch.full = true
        assertEquals(0L, space.usableBytes(folder.root))
    }

    @Test
    fun `without a limit the real sink is used unchanged`() {
        val real = RealSinks()
        val sink = OverriddenSinkFactory(real, Switch()).open(folder.newFile("a.wav"), format)

        assertSame(real.opened, sink)
        sink.close()
    }

    @Test
    fun `a filling disk takes writes up to the limit, then fails with ENOSPC and keeps what was written`() {
        val file = folder.newFile("b.wav")
        val sink = OverriddenSinkFactory(RealSinks(), Switch(limit = 640)).open(file, format)
        val buffer = ShortArray(160)

        sink.write(buffer, 160)
        sink.write(buffer, 160)
        try {
            sink.write(buffer, 1)
            fail("the write past the limit must fail")
        } catch (error: IOException) {
            assertTrue("reported as a full disk", error.isNoSpace())
        }
        assertEquals("the audio written before the disk filled is kept", 640L, sink.finalise())
        assertEquals(44L + 640L, file.length())
    }

    /** The real WAV writer, remembering the sink it opened. */
    private class RealSinks : RecordingSinkFactory {
        var opened: RecordingSink? = null

        override fun open(file: File, format: AudioFormat): RecordingSink =
            WavFileWriter.openWriter(file, format).also { opened = it }
    }
}
