package net.jbglenn.telltype.network.http

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.failure.isSuccess
import net.jbglenn.telltype.network.route.Attempt
import net.jbglenn.telltype.network.route.GateTarget
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * A request to [route] at [target]'s address, with the bearer key when one is set (D60: optional), and
 * `X-Request-Id` = [session] when the id has the shape our gate accepts (1 to 128 of `A-Za-z0-9._:-`). A null
 * [body] makes a GET.
 */
internal fun gateRequest(target: GateTarget, route: GateRoute, session: SessionId?, body: RequestBody?): Request {
    val builder = Request.Builder()
        .url(target.endpoint.baseUrl + route.path)
        .header("Accept", "application/json")
    target.key?.let { builder.header("Authorization", "Bearer ${it.secret}") }
    session?.value?.takeIf { Limits.REQUEST_ID.matches(it) }?.let { builder.header("X-Request-Id", it) }
    return if (body == null) builder.get().build() else builder.post(body).build()
}

/**
 * Reads an answer: a 2xx body goes to [parse] (null from it means malformed), anything else becomes
 * [CallFailure.Http] with an error code and the `Retry-After` header. The code is our gate's `error.code` when
 * the body has one; otherwise `model_not_found` for a 400 or 404 whose body speaks of a model (how stock servers
 * refuse an unknown model name), else null: the status alone then decides (D60). Bodies are read up to
 * [Limits.MAX_BODY_BYTES]; a longer one is malformed. Total except for I/O errors while reading.
 */
internal fun <T> readGateResponse(
    response: Response,
    parse: (body: String, timings: List<ServerTiming>) -> T?,
): Attempt<T> {
    if (!isSuccess(response.code)) {
        val body = response.body.readCapped(Limits.MAX_ERROR_BODY_BYTES)
        val code = body?.let { parseErrorCode(it) ?: modelMissingCode(response.code, it) }
        return Attempt.Failure(CallFailure.Http(response.code, code, parseRetryAfter(response.header("Retry-After"))))
    }
    val value = response.body.readCapped(Limits.MAX_BODY_BYTES)
        ?.let { body -> parse(body, parseServerTiming(response.headers("Server-Timing"))) }
    return if (value == null) Attempt.Failure(CallFailure.MalformedBody) else Attempt.Success(value)
}

/** Error codes `:network` gives itself when the body has none. */
internal object ErrorCodes {
    /** A refused, unknown model name. */
    const val MODEL_NOT_FOUND: String = "model_not_found"
}

private fun modelMissingCode(status: Int, body: String): String? =
    ErrorCodes.MODEL_NOT_FOUND.takeIf { status in Limits.MODEL_STATUSES && body.contains("model", ignoreCase = true) }

/** The body as UTF-8 text, or null when it is longer than [maxBytes]. */
private fun ResponseBody.readCapped(maxBytes: Long): String? {
    val source = source()
    if (source.request(maxBytes + 1)) return null
    return source.buffer.readUtf8()
}

/**
 * Parses `Server-Timing` header values (Appendix C), for example `decode;dur=44.0, stt;dur=125.4`. Entries
 * without a valid non-negative `dur` are skipped; servers that send none give an empty list. Pure.
 */
internal fun parseServerTiming(values: List<String>): List<ServerTiming> = values
    .flatMap { it.split(',') }
    .mapNotNull { entry ->
        val parts = entry.split(';').map(String::trim)
        val name = parts.first().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val duration = parts.drop(1)
            .firstOrNull { it.startsWith("dur=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"')
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return@mapNotNull null
        ServerTiming(name, duration.milliseconds)
    }

/** `Retry-After` in seconds; an HTTP date or anything else gives null. Pure. */
internal fun parseRetryAfter(value: String?): Duration? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

/** Size limits and shapes of what is read and sent. */
private object Limits {
    val REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,128}")

    /** Statuses with which servers refuse an unknown model. */
    val MODEL_STATUSES = setOf(400, 404)

    /** The largest success body read: model lists, transcripts and chat replies are far smaller. */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    /** The largest error body read for its code. */
    const val MAX_ERROR_BODY_BYTES = 64L * 1024
}
