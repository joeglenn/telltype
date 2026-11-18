package net.jbglenn.telltype.platform.emulator

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.FieldProtected
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.platform.insert.LadderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Each automatic ladder step on its own against a plain field, the paste step's clipboard handling, and the typed
 * errors: a password field, a field that went away, a field the user changed before a replace.
 */
class LadderStepsTest {
    @Before
    fun connect() {
        Emulator.connectService()
    }

    @Test
    fun everyStepInsertsOnItsOwnAndReportsItself() {
        val expected = mapOf(
            LadderStep.INPUT_CONNECTION to InsertedVia.INPUT_CONNECTION,
            LadderStep.SET_TEXT to InsertedVia.SET_TEXT,
            LadderStep.PASTE to InsertedVia.PASTE,
        )
        expected.forEach { (step, via) ->
            for (type in listOf(HostField.PLAIN, HostField.COMPOSE, HostField.WEB_INPUT)) {
                val snapshot = InsertSteps.prepare(type, "Hello", TextRange(5, 5))
                val result = InsertSteps.insert("step", type, snapshot, " world", Emulator.inserterFor(step), step.name)
                val outcome = result.let { it as? Outcome.Ok }?.value
                assertEquals("${step.name} on ${type.id}", via, outcome?.via)
                assertEquals("${step.name} on ${type.id}", "Hello world", InsertSteps.fieldText(type))
            }
        }
    }

    /**
     * Paste in a field of this APK's own process, which may read the clipboard while focused: the dictation goes on
     * the clipboard flagged sensitive, and the user's previous clip is put back afterwards.
     */
    @Test
    fun pasteRestoresTheUsersClip() {
        val context = Emulator.instrumentation.targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val intent = Intent(context, OwnFieldActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(OwnFieldActivity.EXTRA_TEXT, "Hello")
        context.startActivity(intent)
        Emulator.device.wait(Until.findObject(By.desc(OwnFieldActivity.OWN_FIELD)), WAIT_MS)
        val snapshot = checkNotNull(Emulator.awaitSnapshot { it?.target?.app?.name == context.packageName })
        Emulator.instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("user", "earlier")) }
        val sensitive = mutableListOf<Boolean>()
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            sensitive +=
                clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        }
        Emulator.instrumentation.runOnMainSync { clipboard.addPrimaryClipChangedListener(listener) }

        val result = InsertSteps.insert(
            "paste-restore",
            HostField.PLAIN,
            snapshot,
            " world",
            Emulator.inserterFor(LadderStep.PASTE),
            "PASTE",
        )

        Emulator.instrumentation.runOnMainSync { clipboard.removePrimaryClipChangedListener(listener) }
        assertTrue(result.toString(), result is Outcome.Ok)
        assertEquals(InsertedVia.PASTE, (result as Outcome.Ok).value.via)
        assertEquals("Hello world", Emulator.device.findObject(By.desc(OwnFieldActivity.OWN_FIELD)).text)
        var restored: CharSequence? = null
        Emulator.instrumentation.runOnMainSync { restored = clipboard.primaryClip?.getItemAt(0)?.text }
        assertEquals("earlier", restored?.toString())
        // Change callbacks arrive later on the main thread and read the clipboard as it is then, so only "a sensitive
        // clip was there at some point" and "the restored clip is not flagged" can be checked from here.
        assertTrue("the dictation was put on the clipboard flagged sensitive: $sensitive", true in sensitive)
        var restoredSensitive: Boolean? = null
        Emulator.instrumentation.runOnMainSync {
            restoredSensitive =
                clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        }
        assertEquals(false, restoredSensitive)
    }

    @Test
    fun aPasswordFieldIsRefusedAndKeepsItsText() {
        val snapshot = FieldSteps.focus(HostField.PASSWORD, text = "secret")

        val result = InsertSteps.insert("password", HostField.PASSWORD, snapshot, "dictated")

        assertEquals(Outcome.Err(FieldProtected), result)
        Emulator.select(0, 0)
        assertEquals(6, Emulator.focusedNode()?.text?.length)
    }

    @Test
    fun aFieldThatWentAwayIsGone() {
        val snapshot = InsertSteps.prepare(HostField.PLAIN, "Hello", TextRange(5, 5))
        Emulator.hostObject("close").click()
        Emulator.awaitSnapshot { it?.target != snapshot.target }

        val result = InsertSteps.insert("gone", HostField.PLAIN, snapshot, " world")

        assertEquals(Outcome.Err(FieldGone), result)
    }

    @Test
    fun aReplaceAfterTheUserEditedIsRefused() {
        val snapshot = InsertSteps.prepare(HostField.PLAIN, "Hello", TextRange(5, 5))
        val receipt = (InsertSteps.insert("changed", HostField.PLAIN, snapshot, " world") as Outcome.Ok).value
        HostField.PLAIN.find()!!.text = "Something else"
        assertTrue(Emulator.device.wait(Until.hasObject(By.text("Something else")), WAIT_MS))

        val result = runBlocking { Emulator.graph.textInserter.replace(receipt, " raw") }

        Emulator.record("changed", HostField.PLAIN.id, "replace", result.toString())
        assertEquals(Outcome.Err(FieldChanged), result)
        assertEquals("Something else", InsertSteps.fieldText(HostField.PLAIN))
    }

    private companion object {
        val WAIT_MS = 10.seconds.inWholeMilliseconds
    }
}
