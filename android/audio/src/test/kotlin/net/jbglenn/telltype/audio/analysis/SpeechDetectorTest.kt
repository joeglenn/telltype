package net.jbglenn.telltype.audio.analysis

import net.jbglenn.telltype.audio.Signal
import net.jbglenn.telltype.audio.Signals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechDetectorTest {
    private fun verdictOf(signal: Signal, millis: Int = 2_000, chunk: Int = 320): SpeechVerdict {
        val detector = SpeechDetector()
        val total = millis * 16
        val buffer = ShortArray(chunk)
        var position = 0
        while (position < total) {
            val count = minOf(chunk, total - position)
            for (index in 0 until count) buffer[index] = signal.sample((position + index).toLong())
            detector.accept(buffer, count)
            position += count
        }
        return detector.verdict()
    }

    @Test
    fun digitalSilenceIsNone() {
        assertEquals(SpeechVerdict.NONE, verdictOf(Signals.zeros))
    }

    @Test
    fun aQuietRoomIsNone() {
        assertEquals(SpeechVerdict.NONE, verdictOf(Signals.noise(dbfs = -75.0)))
    }

    @Test
    fun anAudibleNoiseFloorWithoutSpeechIsFaint() {
        assertEquals(SpeechVerdict.FAINT, verdictOf(Signals.noise(dbfs = -58.0)))
    }

    @Test
    fun toneBurstsAtSpeechLevelAreClear() {
        val bursts = Signals.bursts(Signals.tone(dbfs = -30.0), onMillis = 100, periodMillis = 500)

        assertEquals(SpeechVerdict.CLEAR, verdictOf(bursts))
    }

    @Test
    fun softSpeechOverANoisyRoomIsClear() {
        val speech = Signals.bursts(Signals.tone(dbfs = -45.0), onMillis = 200, periodMillis = 400)

        assertEquals(SpeechVerdict.CLEAR, verdictOf(Signals.mix(speech, Signals.noise(dbfs = -60.0))))
    }

    @Test
    fun aSingleClickIsNotSpeech() {
        val click = Signals.after(60, Signals.tone(dbfs = -20.0), Signals.zeros)

        assertEquals(SpeechVerdict.NONE, verdictOf(click))
    }

    @Test
    fun aTapAboveTheSpeechLevelButShorterThan150MsIsOnlyFaint() {
        val tap = Signals.after(140, Signals.tone(dbfs = -20.0), Signals.noise(dbfs = -62.0))

        assertEquals(SpeechVerdict.FAINT, verdictOf(tap))
    }

    @Test
    fun aDcOffsetIsNotSound() {
        assertEquals(SpeechVerdict.NONE, verdictOf(Signals.dc(12_000)))
    }

    @Test
    fun speechRidingOnADcOffsetIsStillClear() {
        assertEquals(SpeechVerdict.CLEAR, verdictOf(Signals.mix(Signals.tone(dbfs = -35.0), Signals.dc(8_000))))
    }

    @Test
    fun framesMayStraddleBuffers() {
        val bursts = Signals.bursts(Signals.tone(dbfs = -40.0), onMillis = 80, periodMillis = 400)

        assertEquals(verdictOf(bursts, chunk = 320), verdictOf(bursts, chunk = 7))
        assertEquals(SpeechVerdict.CLEAR, verdictOf(bursts, chunk = 7))
    }

    @Test
    fun thresholdsSitAtTheirStatedLevels() {
        val justBelow = Signals.tone(dbfs = SpeechDetector.SPEECH_FRAME_DBFS - 0.5)
        val justAbove = Signals.tone(dbfs = SpeechDetector.SPEECH_FRAME_DBFS + 0.5)
        val belowSignal = Signals.tone(dbfs = SpeechDetector.SIGNAL_FRAME_DBFS - 0.5)

        assertEquals(SpeechVerdict.FAINT, verdictOf(justBelow))
        assertEquals(SpeechVerdict.CLEAR, verdictOf(justAbove))
        assertEquals(SpeechVerdict.NONE, verdictOf(belowSignal))
    }
}
