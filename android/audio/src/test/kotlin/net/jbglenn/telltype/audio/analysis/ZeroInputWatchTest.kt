package net.jbglenn.telltype.audio.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroInputWatchTest {
    private fun ZeroInputWatch.feed(value: Short, count: Int) {
        val buffer = ShortArray(count) { value }
        accept(buffer, count)
    }

    @Test
    fun allZerosAreReportedAtExactlyHalfASecond() {
        val watch = ZeroInputWatch()

        watch.feed(0, 7_999)
        assertFalse(watch.inUse)

        watch.feed(0, 1)
        assertTrue(watch.inUse)
        assertFalse(watch.lost)
    }

    @Test
    fun warmUpZerosFollowedBySoundAreNotInUse() {
        val watch = ZeroInputWatch()

        watch.feed(0, 3_200)
        watch.feed(5, 1)
        watch.feed(0, 4_799)

        assertFalse(watch.inUse)
        assertTrue(watch.sawSignal)
    }

    @Test
    fun oneSecondOfZerosAfterSoundMeansTheMicrophoneWasLost() {
        val watch = ZeroInputWatch()
        watch.feed(100, 320)

        watch.feed(0, 15_999)
        assertFalse(watch.lost)

        watch.feed(0, 1)
        assertTrue(watch.lost)
        assertFalse(watch.inUse)
    }

    @Test
    fun soundResetsTheZeroRun() {
        val watch = ZeroInputWatch()
        watch.feed(100, 320)
        watch.feed(0, 15_000)
        watch.feed(-1, 1)

        watch.feed(0, 15_000)

        assertFalse(watch.lost)
    }
}
