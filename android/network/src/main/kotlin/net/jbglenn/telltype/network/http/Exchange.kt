package net.jbglenn.telltype.network.http

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.failure.classifyTransport
import net.jbglenn.telltype.network.route.Attempt
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Sends [request] with [client] and reads the answer with [read], bounded by [timeout] for the whole call
 * (connect, upload, waiting, download). The call runs on OkHttp's threads; [read] runs there too, so a stalled
 * body never blocks a coroutine dispatcher. Cancelling the coroutine cancels the call at once, also mid-upload.
 *
 * OkHttp's timeouts are wall-clock timers by nature; everything around them (retry pauses, breakers) runs on
 * injected time. [read] must not throw anything but [IOException].
 */
internal suspend fun <T> send(
    client: OkHttpClient,
    request: Request,
    timeout: Duration,
    read: (Response) -> T,
): Sent<T> {
    val progress = ConnectionProgress()
    val call = client.newCall(request.newBuilder().tag(ConnectionProgress::class.java, progress).build())
    call.timeout().timeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return try {
        Sent.Answered(call.await(read))
    } catch (error: IOException) {
        Sent.Failed(error, connected = progress.connected)
    }
}

/** [send], with a failure without an HTTP answer classified as [CallFailure.Transport]. */
internal suspend fun <T> exchange(
    client: OkHttpClient,
    request: Request,
    timeout: Duration,
    read: (Response) -> Attempt<T>,
): Attempt<T> = when (val sent = send(client, request, timeout, read)) {
    is Sent.Answered -> sent.value
    is Sent.Failed -> Attempt.Failure(CallFailure.Transport(classifyTransport(sent.error), sent.connected))
}

private suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    response.use(read)
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                    return
                }
                continuation.resume(value)
            }
        },
    )
}
