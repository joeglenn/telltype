package net.jbglenn.telltype.core.model

import kotlin.time.Duration

/**
 * Identifies one dictation session, from the moment its recording starts until it ends in Done or Problem.
 * Also the history row id and the `X-Request-Id` sent to the server, so a phone log line, a history entry and a server
 * log line can be matched. Made by `IdGenerator`; opaque to everyone else.
 */
@JvmInline
public value class SessionId(public val value: String)

/**
 * Identifies one recording file in app-private storage (and the pending job that owns it while queued).
 * Made by `IdGenerator`; only `RecordingStore` maps it to a file.
 */
@JvmInline
public value class RecordingId(public val value: String)

/**
 * Opaque reference to one editable text field on screen. Minted and resolved only by `:platform`; every other
 * layer may store, compare and pass it, never interpret it.
 *
 * Invariants the platform guarantees: two handles are equal exactly when they refer to the same field (same
 * window, same node), a handle stays the same for as long as that field exists, and a handle is never reused
 * for a different field during the process lifetime. "Focus moved" therefore means "handle changed".
 */
@JvmInline
public value class FieldHandle(public val token: String)

/** An Android application package name, for example `com.google.android.gm`. */
@JvmInline
public value class AppPackage(public val name: String)

/**
 * One entry of the `Server-Timing` header the server returns (docs/PLAN.md Appendix C), for example
 * `stt;dur=340` becomes `ServerTiming("stt", 340.milliseconds)`.
 *
 * @property name the metric name as sent by the server (`decode`, `stt`, `llm`, ...).
 * @property duration the server-side time for that step.
 */
public data class ServerTiming(public val name: String, public val duration: Duration)

/** Prints only the length of [this], for `toString` overrides of types that hold dictated text or context. */
internal fun String.lengthOnly(): String = "‹$length chars›"
