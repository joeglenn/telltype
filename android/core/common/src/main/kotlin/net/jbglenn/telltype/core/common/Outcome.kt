package net.jbglenn.telltype.core.common

/**
 * The result of a fallible operation that crosses a module boundary (docs/PLAN.md section 6.5).
 *
 * Exceptions never cross a module boundary: an adapter catches them at its edge and returns [Err] with a typed
 * error. `E` is the narrowest error type the operation can produce (for example `SttError`), so callers can
 * handle every case with an exhaustive `when`.
 */
public sealed interface Outcome<out T, out E> {
    /** The operation succeeded with [value]. */
    public data class Ok<out T>(val value: T) : Outcome<T, Nothing>

    /** The operation failed with [error]. */
    public data class Err<out E>(val error: E) : Outcome<Nothing, E>
}

/** Transforms the success value; an error passes through unchanged. */
public inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

/** Transforms the error; a success passes through unchanged. */
public inline fun <T, E, F> Outcome<T, E>.mapError(transform: (E) -> F): Outcome<T, F> = when (this) {
    is Outcome.Ok -> this
    is Outcome.Err -> Outcome.Err(transform(error))
}

/** Chains a second fallible step that runs only after a success. */
public inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> = when (this) {
    is Outcome.Ok -> transform(value)
    is Outcome.Err -> this
}

/** Collapses both cases into one value. */
public inline fun <T, E, R> Outcome<T, E>.fold(onOk: (T) -> R, onErr: (E) -> R): R = when (this) {
    is Outcome.Ok -> onOk(value)
    is Outcome.Err -> onErr(error)
}

/** The success value, or null after an error. Use only where the error really does not matter. */
public fun <T, E> Outcome<T, E>.getOrNull(): T? = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> null
}
