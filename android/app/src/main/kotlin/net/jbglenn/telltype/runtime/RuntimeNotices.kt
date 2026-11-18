package net.jbglenn.telltype.runtime

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.domain.port.NotifierPort

/**
 * The notifications for what the bubble cannot show itself (docs/PLAN.md section 10): the accessibility service
 * went away ([accessibilityOff]), and a dictation ended in a problem while no bubble could show it
 * ([problemsWhileHidden]).
 */
internal class RuntimeNotices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: NotifierPort,
) {
    /**
     * Posts [Notice.AccessibilityOff] (with Fix) when the service was connected and goes away for good: switched
     * off by the user, or by the system after a crash or an update. A disconnection the system undoes by itself
     * (UI Automator suppressing services for a moment, a rebind) is not one: the notice waits [OFF_GRACE] and is
     * posted only when the service is still disconnected and switched off in the secure setting then (the setting,
     * not the enabled-services list, which also leaves the service out while services are suppressed). Each
     * connection removes the notice again (also one left by an earlier process). Runs until cancelled.
     */
    suspend fun accessibilityOff(connected: StateFlow<Boolean>) {
        var wasConnected = false
        connected.collectLatest { now ->
            if (now) {
                wasConnected = true
                notifier.cancel(Notice.AccessibilityOff)
            } else if (wasConnected) {
                delay(OFF_GRACE)
                if (!switchedOn()) {
                    wasConnected = false
                    notifier.post(Notice.AccessibilityOff)
                }
            }
        }
    }

    /**
     * Posts [Notice.ProblemWhileHidden] when the session enters a problem while no bubble shows (the field is gone,
     * the app is excluded or snoozed), so the dictation does not fail silently. One notice per problem; none for a
     * problem that appeared on a visible bubble. [states] pairs each state with whether the bubble shows for it.
     * Runs until cancelled.
     */
    suspend fun problemsWhileHidden(states: Flow<Pair<SessionState, Boolean>>) {
        var current: SessionState.Problem? = null
        states.collect { (state, shown) ->
            val problem = state as? SessionState.Problem
            if (problem != null && !shown && !sameProblem(problem, current)) {
                notifier.post(Notice.ProblemWhileHidden(problem.error))
            }
            current = problem
        }
    }

    /** Whether the secure setting lists this app's accessibility service. */
    private fun switchedOn(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled.orEmpty().split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.packageName == context.packageName
        }
    }

    /** The same problem with another focus is not a new one. */
    private fun sameProblem(problem: SessionState.Problem, before: SessionState.Problem?): Boolean =
        before != null && before.session?.id == problem.session?.id && before.error == problem.error

    companion object {
        /** How long a disconnection must last before it counts as the service being switched off. */
        val OFF_GRACE = 2.seconds
    }
}
