package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.domain.session.FocusInput
import net.jbglenn.telltype.domain.session.evaluateFocus

/**
 * The focus feed: the focused field, the settings, the permissions, the app rules and the snooze, combined through
 * `evaluateFocus` into the [Focus] the reducer sees, de-duplicated (the field source already debounces). A snooze
 * ends by itself at its end time. A server counts as configured when `ServerConfig.isConfigured`, or when the fake
 * engines of the advanced settings are on (no on-device engine exists yet).
 */
internal class FocusFeed(
    private val sources: FocusSources,
    private val stores: CoordinatorStores,
    private val clock: Clock,
) {
    /** Every change of the focus, starting with the current one. */
    fun focus(): Flow<Focus> = combine(
        sources.focusedField.focused,
        stores.settings.settings,
        sources.permissions.status,
        stores.appRules.rules,
        snoozed(),
    ) { snapshot, settings, permissions, rules, snoozed ->
        val rule = snapshot?.let { field -> rules.firstOrNull { it.app == field.target.app } }
        val configured = settings.server.isConfigured || settings.advanced.useFakeEngines
        evaluateFocus(FocusInput(snapshot, rule, settings.bubble, snoozed, permissions, configured))
    }.distinctUntilChanged()

    private fun snoozed(): Flow<Boolean> = channelFlow {
        sources.snooze.snoozedUntil.collectLatest { until ->
            val left = until?.let { it - clock.now() } ?: Duration.ZERO
            send(left.isPositive())
            if (left.isPositive()) {
                delay(left)
                send(false)
            }
        }
    }
}
