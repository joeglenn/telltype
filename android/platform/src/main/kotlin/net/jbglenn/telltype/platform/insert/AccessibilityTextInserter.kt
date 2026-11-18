package net.jbglenn.telltype.platform.insert

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.InsertNotConfirmed
import net.jbglenn.telltype.core.model.error.InsertionError
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.TextInserter
import net.jbglenn.telltype.platform.field.FieldAccess

/**
 * [TextInserter] over the accessibility service: resolves the target's handle, reads "Never use the clipboard"
 * and runs the [InsertionLadder] on the accessibility dispatcher. The whole insertion is bounded by [timeout]
 * ([InsertNotConfirmed] when it is missed): the ladder is cancelled at its next wait, so no later step starts. A
 * cross-process call already under way finishes first (each is bounded by the framework's own interaction timeout);
 * the insertion is deliberately not detached from the caller, so text can never land after a hand-off.
 *
 * @param resolve the field behind a handle, null when unknown or forgotten ([FieldGone]).
 */
internal class AccessibilityTextInserter(
    private val resolve: (FieldHandle) -> FieldAccess?,
    private val ladder: InsertionLadder,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : TextInserter {
    override suspend fun insert(target: FieldTarget, text: String): Outcome<InsertReceipt, InsertionError> {
        val request = InsertRequest(text, range = null, expected = null, requireFocus = true, allowClipboard())
        return run(target, request)
    }

    override suspend fun replace(receipt: InsertReceipt, text: String): Outcome<InsertReceipt, InsertionError> {
        val range = receipt.range
        return if (range == null || !receipt.via.typed) {
            Outcome.Err(FieldChanged)
        } else {
            val request = InsertRequest(text, range, expected = receipt.text, requireFocus = false, allowClipboard())
            run(receipt.target, request)
        }
    }

    private suspend fun run(target: FieldTarget, request: InsertRequest): Outcome<InsertReceipt, InsertionError> {
        val outcome = withContext(dispatchers.accessibility) {
            withTimeoutOrNull(timeout) {
                resolve(target.handle)?.let { field -> ladder.run(field, request) } ?: Outcome.Err(FieldGone)
            }
        } ?: Outcome.Err(InsertNotConfirmed)
        logger.info(TAG) { "insert into ${target.handle.token}: ${outcome.describe()}" }
        return outcome.map { InsertReceipt(target, it.landed ?: request.text, it.replaced, it.range, it.via) }
    }

    private suspend fun allowClipboard(): Boolean = !settings.current().insertion.neverUseClipboard

    private fun Outcome<Inserted, InsertionError>.describe(): String = when (this) {
        is Outcome.Ok ->
            "${value.via.id} at ${value.range}" +
                if (value.landed != null) " (adapted by the field)" else ""

        is Outcome.Err -> error.code
    }

    private companion object {
        val TAG = LogTag("TextInserter")
        val DEFAULT_TIMEOUT = 5.seconds
    }
}
