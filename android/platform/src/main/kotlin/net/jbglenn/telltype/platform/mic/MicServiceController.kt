package net.jbglenn.telltype.platform.mic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.domain.port.MicrophoneServicePort
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge

/**
 * [MicrophoneServicePort] over [MicService]. Starts it from the accessibility service's context when connected
 * (its binding is what lets a background process start a microphone service, docs/PLAN.md section 7.3), with a
 * plain `startService`, so a refused foreground start never crashes the process: the service reports the refusal
 * and stops itself. Waits for the service's answer at most [timeout].
 */
internal class MicServiceController(
    private val context: Context,
    private val bridge: AccessibilityServiceBridge,
    private val status: MicServiceStatus,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : MicrophoneServicePort {
    override suspend fun start(): Outcome<Unit, CaptureError> = withContext(dispatchers.main) {
        when {
            !microphoneGranted() -> Outcome.Err(MicrophonePermissionMissing)
            status.current.value == MicServiceState.Running -> Outcome.Ok(Unit)
            else -> requestStart()
        }
    }

    override suspend fun stop() {
        withContext(dispatchers.main) {
            context.stopService(Intent(context, MicService::class.java))
        }
    }

    private suspend fun requestStart(): Outcome<Unit, CaptureError> {
        val starter = bridge.service.value ?: context
        status.report(MicServiceState.Starting)
        val sent = try {
            starter.startService(Intent(starter, MicService::class.java).setAction(MicService.ACTION_START)) != null
        } catch (e: IllegalStateException) {
            logger.warn(TAG, e) { "microphone service start not allowed" }
            false
        } catch (e: SecurityException) {
            logger.warn(TAG, e) { "microphone service start refused" }
            false
        }
        val answer = if (sent) withTimeoutOrNull(timeout) { status.current.first { it.isAnswer() } } else null
        val viaService = starter !== context
        logger.info(TAG) { "microphone service start: ${answer ?: "no answer"} (from accessibility: $viaService)" }
        if (answer != MicServiceState.Running) status.report(MicServiceState.Stopped)
        return if (answer == MicServiceState.Running) Outcome.Ok(Unit) else Outcome.Err(RecorderFailed)
    }

    private fun microphoneGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun MicServiceState.isAnswer(): Boolean = this == MicServiceState.Running || this is MicServiceState.Refused

    private companion object {
        val TAG = LogTag("MicService")
        val DEFAULT_TIMEOUT = 3.seconds
    }
}
