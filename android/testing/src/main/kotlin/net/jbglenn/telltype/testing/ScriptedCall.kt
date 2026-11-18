package net.jbglenn.telltype.testing

import kotlin.time.Duration
import kotlinx.coroutines.delay
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.StorageError

/**
 * One scripted method of a fake: records every argument, waits [delay] on the caller's dispatcher (virtual time
 * under `runTest`), then answers with the next queued result, or with the standing answer when none is queued.
 * Not thread-safe: use it from one test dispatcher.
 *
 * @param initial the standing answer until [returns] or [answers] replaces it; it may use the argument.
 */
public class ScriptedCall<A, R>(initial: (A) -> R) {
    private var standing: (A) -> R = initial
    private val queued = ArrayDeque<R>()
    private val recorded = mutableListOf<A>()

    /** How long each call waits before answering. */
    public var delay: Duration = Duration.ZERO

    /** The arguments of every call so far, oldest first. */
    public val calls: List<A> get() = recorded.toList()

    /** Answers every later call with [result]. */
    public fun returns(result: R) {
        standing = { result }
    }

    /** Answers every later call by applying [answer] to its argument. */
    public fun answers(answer: (A) -> R) {
        standing = answer
    }

    /** Answers the next calls with [results], in order, before the standing answer applies again. */
    public fun returnsNext(vararg results: R) {
        queued.addAll(results)
    }

    /** Records [argument], waits [delay] and answers. Called by the fake's port method. */
    public suspend fun answer(argument: A): R {
        recorded += argument
        if (delay.isPositive()) delay(delay)
        return if (queued.isEmpty()) standing(argument) else queued.removeFirst()
    }
}

/**
 * Failure and delay switches shared by the in-memory repository fakes.
 * While [failure] is set, every operation fails with it and changes nothing.
 */
public class StorageFaults {
    /** The error every operation returns; null for normal behaviour. */
    public var failure: StorageError? = null

    /** How long every suspend operation waits first. */
    public var delay: Duration = Duration.ZERO

    /** Waits, then runs [operation] unless [failure] is set. */
    public suspend fun <T> run(operation: () -> T): Outcome<T, StorageError> {
        if (delay.isPositive()) delay(delay)
        return failure?.let { Outcome.Err(it) } ?: Outcome.Ok(operation())
    }
}
