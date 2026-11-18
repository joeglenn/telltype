package net.jbglenn.telltype.ui.overlay

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BubbleUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<BubbleEvent>()

    private fun show(name: String) {
        compose.setContent {
            TelltypePreviewTheme {
                Bubble(
                    scene = BubbleScenes.all.getValue(name),
                    onEvent = { if (it !is BubbleEvent.Resized) events += it },
                    motion = TelltypeMotion(reduced = true),
                )
            }
        }
    }

    @Test
    fun everyControlInEveryStateIsLabelledAndAtLeast48dp() {
        var controls = 0
        BubbleScenes.all.forEach { (name, scene) ->
            compose.setContentOnce(scene)
            val clickable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            controls += clickable.size
            clickable.indices.forEach { index ->
                val node = compose.onAllNodes(hasClickAction())[index]
                val semantics = node.fetchSemanticsNode()
                val minimum = with(compose.density) { 48.dp.toPx() } - 0.5f
                assertTrue("$name control $index is 48 dp high", semantics.touchBoundsInRoot.height >= minimum)
                assertTrue("$name control $index is 48 dp wide", semantics.touchBoundsInRoot.width >= minimum)
                val config = semantics.config
                val label = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    config.getOrNull(SemanticsProperties.Text).orEmpty()
                assertTrue("$name control $index has a label", label.isNotEmpty())
            }
        }
        assertTrue("controls were found", controls > BubbleScenes.all.size)
    }

    @Test
    fun stateIsAnnouncedPolitely() {
        show("workingTranscribing")

        compose.onNodeWithContentDescription("Dictation bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Transcribing"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun listeningCancelAndFinishEmitTheirEvents() {
        show("listening")

        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.onNodeWithContentDescription("Finish").performClick()

        assertEquals(
            listOf(session(SessionEvent.CancelTapped), session(SessionEvent.FinishTapped)),
            events,
        )
    }

    @Test
    fun releaseToCancelIsSpoken() {
        show("holdReleaseToCancel")

        compose.onNodeWithContentDescription("Dictation bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Release to cancel"))
    }

    @Test
    fun doneChipsAreTheActionsInOrder() {
        show("doneRawUndo")

        compose.onAllNodesWithText("Raw").assertCountEquals(1)
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Raw").performClick()

        assertEquals(
            listOf(
                session(SessionEvent.ActionTapped(BubbleAction.UNDO)),
                session(SessionEvent.ActionTapped(BubbleAction.RAW)),
            ),
            events,
        )
    }

    @Test
    fun problemShowsChipsAndDismissIsTheCloseButton() {
        show("problemRetry")

        compose.onNodeWithText("The server is warming up").assertExists()
        compose.onAllNodesWithText("Dismiss").assertCountEquals(0)
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithContentDescription("Dismiss").performClick()

        assertEquals(
            listOf(
                session(SessionEvent.ActionTapped(BubbleAction.RETRY)),
                session(SessionEvent.ActionTapped(BubbleAction.DISMISS)),
            ),
            events,
        )
    }

    @Test
    fun transientChipsOfferTheirAction() {
        show("chipLearnOffer")
        compose.onNodeWithText("Learn").performClick()
        assertEquals(session(SessionEvent.LearnAccepted(Correction("cube control", "kubectl"))), events.single())
    }

    @Test
    fun explanationChipOpensTheFix() {
        show("chipExplanation")
        compose.onNodeWithText("Fix").performClick()
        assertEquals(session(SessionEvent.ActionTapped(BubbleAction.FIX)), events.single())
    }

    @Test
    fun accessibilityClickStartsAndCustomActionsSnooze() {
        show("ready")
        val bubble = compose.onNodeWithContentDescription("Dictation bubble")

        bubble.performSemanticsAction(SemanticsActions.OnClick)
        val actions = bubble.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        actions.first { it.label == "Hide for 1 hour" }.action()
        actions.first { it.label == "Never show in this app" }.action()

        assertEquals(
            listOf(session(SessionEvent.MicPressed), BubbleEvent.SnoozeRequested, BubbleEvent.NeverShowInThisApp),
            events,
        )
    }

    @Test
    fun tapPressesAndReleases() {
        show("ready")

        compose.onNodeWithContentDescription("Dictation bubble").performTouchInput {
            down(center)
            up()
        }

        assertEquals(listOf(session(SessionEvent.MicPressed), session(SessionEvent.MicReleased)), events)
    }

    @Test
    fun holdBecomesHoldToTalkAndSlidingAwayCancels() {
        show("ready")

        compose.onNodeWithContentDescription("Dictation bubble").performTouchInput {
            down(center)
            advanceEventTime(400)
            moveBy(Offset(0f, -400f))
            up()
        }

        assertEquals(
            listOf(
                session(SessionEvent.MicPressed),
                session(SessionEvent.HoldDetected),
                BubbleEvent.HoldFeedback(true),
                BubbleEvent.HoldFeedback(false),
                session(SessionEvent.CancelTapped),
            ),
            events,
        )
    }

    @Test
    fun dragMovesTheBubbleAndStartsNoSession() {
        show("ready")

        compose.onNodeWithContentDescription("Dictation bubble").performTouchInput {
            down(center)
            moveBy(Offset(200f, 0f))
            up()
        }

        assertEquals(
            "a press that becomes a drag starts no session",
            emptyList<BubbleEvent>(),
            events.filterIsInstance<BubbleEvent.Session>(),
        )
        assertTrue(events[0] is BubbleEvent.Moved)
        val last = events.filterIsInstance<BubbleEvent.Moved>().last()
        assertEquals("the window goes 200 px right of where it was at the press", 200f, last.x - last.grabX, 1f)
        assertEquals(BubbleEvent.MoveEnded, events.last())
    }

    @Test
    fun longPressOnUnavailableRequestsTheMenuInsteadOfHoldToTalk() {
        show("unavailable")

        compose.onNodeWithContentDescription("Dictation bubble").performTouchInput {
            down(center)
            advanceEventTime(400)
            up()
        }

        assertEquals(listOf(session(SessionEvent.MicPressed), BubbleEvent.MenuRequested), events)
    }

    @Test
    fun longPressOnReadyStaysHoldToTalk() {
        show("ready")

        compose.onNodeWithContentDescription("Dictation bubble").performTouchInput {
            down(center)
            advanceEventTime(400)
            up()
        }

        assertEquals(
            listOf(
                session(SessionEvent.MicPressed),
                session(SessionEvent.HoldDetected),
                session(SessionEvent.MicReleased),
            ),
            events,
        )
    }

    @Test
    fun menuItemsEmitTheirEvents() {
        show("menu")

        compose.onNodeWithText("Never show in this app").performClick()
        compose.onNodeWithText("Hide for 1 hour").performClick()
        compose.onNodeWithText("Close").performClick()

        assertEquals(
            listOf(BubbleEvent.NeverShowInThisApp, BubbleEvent.SnoozeRequested, BubbleEvent.MenuDismissed),
            events,
        )
    }

    @Test
    fun overlayOpensTheMenuOnLongPressAndClosesItAfterAChoice() {
        compose.setContent {
            TelltypePreviewTheme {
                BubbleOverlay(
                    state = SessionState.Unavailable(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
                    reachability = ServerReachability.Unknown,
                    chip = null,
                    side = BubbleSide.RIGHT,
                    levels = WaveformLevels(),
                    onEvent = { if (it !is BubbleEvent.Resized) events += it },
                )
            }
        }
        compose.onAllNodesWithText("Never show in this app").assertCountEquals(0)

        compose.onNodeWithContentDescription("Dictation bubble").performTouchInput {
            down(center)
            advanceEventTime(400)
            up()
        }
        compose.onNodeWithText("Never show in this app").performClick()

        compose.onAllNodesWithText("Never show in this app").assertCountEquals(0)
        assertEquals(
            listOf(session(SessionEvent.MicPressed), BubbleEvent.MenuRequested, BubbleEvent.NeverShowInThisApp),
            events,
        )
    }

    private fun session(event: SessionEvent) = BubbleEvent.Session(event)

    private var current: MutableState<BubbleScene>? = null

    private fun ComposeContentTestRule.setContentOnce(scene: BubbleScene) {
        val holder = current
        if (holder == null) {
            val state = mutableStateOf(scene)
            current = state
            setContent {
                TelltypePreviewTheme {
                    Bubble(scene = state.value, onEvent = {}, motion = TelltypeMotion(reduced = true))
                }
            }
        } else {
            holder.value = scene
        }
        waitForIdle()
    }
}
