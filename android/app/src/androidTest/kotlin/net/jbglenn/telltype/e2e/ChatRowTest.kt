package net.jbglenn.telltype.e2e

import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import net.jbglenn.telltype.e2e.Device.HOST
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Board B2: in Telegram the bubble covered the microphone button at the right end of the message row, beside the
 * field and outside its rectangle. At its default place the bubble now keeps out of the focused field's row. Needs a
 * configured app; starts from the default place (the bubble switched off and on forgets a dragged place).
 */
@RunWith(AndroidJUnit4::class)
class ChatRowTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        BubbleVisibility.forgetDraggedPlace()
    }

    @Test
    fun theBubbleSitsAboveAChatRowAndCoversNeitherTheFieldNorItsSendButton() {
        val field = Device.openChatField()
        Bubble.expectState("Ready")
        val bubble = checkNotNull(BubbleVisibility.settledBounds()) { "no bubble" }
        val row = field.visibleBounds
        val send = Device.await(By.res(HOST, "send")).visibleBounds
        Device.screenshot("chat-row")
        Log.i(TAG, "chat row: bubble $bubble, field $row, send $send, window ${BubbleWindows.bubble()}")
        assertFalse("the bubble $bubble covers the send button $send", Rect.intersects(bubble, send))
        assertFalse("the bubble $bubble covers the field $row", Rect.intersects(bubble, row))
        val rowTop = minOf(row.top, send.top)
        assertTrue("the bubble $bubble is above the row (top $rowTop)", bubble.bottom <= rowTop)
    }
}
