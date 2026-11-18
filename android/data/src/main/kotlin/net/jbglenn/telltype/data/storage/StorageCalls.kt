package net.jbglenn.telltype.data.storage

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.error
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull

/**
 * Runs storage work on the io dispatcher and turns every storage failure into a [StorageError], so nothing throws
 * across the module boundary (docs/PLAN.md section 10). Cancellation is never caught.
 *
 * @param tag the repository's log tag. Messages name the operation and the error code, never row content.
 */
internal class StorageCalls(
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val tag: LogTag,
) {
    /** Runs [block]; a storage exception becomes [StorageFull] or [StorageFailed]. */
    suspend fun <T> run(operation: String, block: suspend () -> T): Outcome<T, StorageError> =
        withContext(dispatchers.io) {
            try {
                Outcome.Ok(block())
            } catch (failure: SQLiteException) {
                failed(operation, failure)
            } catch (failure: IOException) {
                failed(operation, failure)
            } catch (failure: IllegalStateException) {
                failed(operation, failure)
            }
        }

    /** Like [run], for a block whose own result may already be a storage error. */
    suspend fun <T> runFlat(
        operation: String,
        block: suspend () -> Outcome<T, StorageError>,
    ): Outcome<T, StorageError> = when (val outcome = run(operation, block)) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> outcome
    }

    /**
     * Maps each list of rows with [toModel] on the io dispatcher, skipping (and logging) rows it cannot read. A
     * database failure ends the flow after one empty list, logged as an error.
     */
    fun <R : Any, M : Any> list(operation: String, rows: Flow<List<R>>, toModel: (R) -> M?): Flow<List<M>> = rows
        .map { list ->
            val models = list.mapNotNull(toModel)
            if (models.size != list.size) {
                logger.warn(tag, null) { "$operation: skipped ${list.size - models.size} unreadable rows" }
            }
            models
        }
        .catch { failure ->
            if (failure !is SQLiteException && failure !is IllegalStateException) throw failure
            logger.error(tag, failure) { "$operation failed: ${storageErrorOf(failure).code}" }
            emit(emptyList())
        }
        .flowOn(dispatchers.io)

    private fun failed(operation: String, failure: Exception): Outcome<Nothing, StorageError> {
        val error = storageErrorOf(failure)
        logger.error(tag, failure) { "$operation failed: ${error.code}" }
        return Outcome.Err(error)
    }
}

/** [StorageFull] when the cause is a full disk or database, [StorageFailed] otherwise. Pure. */
internal fun storageErrorOf(failure: Throwable): StorageError {
    val chain = generateSequence(failure) { it.cause }.take(Causes.MAX_CAUSE_DEPTH)
    val full = chain.any { cause ->
        cause is SQLiteFullException || cause.message.orEmpty().let { "ENOSPC" in it || "No space left" in it }
    }
    return if (full) StorageFull else StorageFailed
}

/** How far the cause chain of a failure is followed. */
private object Causes {
    const val MAX_CAUSE_DEPTH = 8
}
