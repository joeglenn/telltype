package net.jbglenn.telltype.ui.screens.setup

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.system.PermissionStatus

/** The five steps of setup (docs/PLAN.md section 5.3), in order. */
enum class SetupStep {
    /** Address, optional text model address and key, certificate fingerprint, Test. */
    SERVER,

    /** Microphone (and local network on Android 17). */
    MICROPHONE,

    /** Notifications. */
    NOTIFICATIONS,

    /** The accessibility service, with the restricted-settings walk-through. */
    ACCESSIBILITY,

    /** A field to try the real bubble in. */
    PRACTICE,
}

/**
 * Setup's state. The key is held only here while typed and is never printed ([toString]) or put in saved state.
 *
 * @property step the visible step.
 * @property address the server address field (speech, and text unless [textAddress] is filled).
 * @property addressError what is wrong with [address] after Test, null when nothing.
 * @property textAddress the optional text model address under Advanced (D60); empty means [address].
 * @property textAddressError what is wrong with [textAddress] after Test, null when nothing.
 * @property advancedOpen the Advanced part with [textAddress] is shown.
 * @property key the optional key being typed; empty keeps the saved one (or none).
 * @property keySaved a key is already stored.
 * @property formError the validation message of the server form, null when none.
 * @property connection the result of Test.
 * @property permissions the permission snapshot; null before the first read.
 */
@Immutable
data class SetupUiState(
    val step: SetupStep = SetupStep.SERVER,
    val address: String = "",
    @param:StringRes val addressError: Int? = null,
    val textAddress: String = "",
    @param:StringRes val textAddressError: Int? = null,
    val advancedOpen: Boolean = false,
    val key: String = "",
    val keySaved: Boolean = false,
    @param:StringRes val formError: Int? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val permissions: PermissionStatus? = null,
) {
    /** Whether Next may be pressed on this step (server connected, microphone granted; the rest may be skipped). */
    val canContinue: Boolean
        get() = when (step) {
            SetupStep.SERVER -> connection is ConnectionState.Connected || connection == ConnectionState.Saved
            SetupStep.MICROPHONE -> permissions?.microphoneGranted == true
            SetupStep.NOTIFICATIONS, SetupStep.ACCESSIBILITY, SetupStep.PRACTICE -> true
        }

    /** Whether this step is done, so Next reads "Next" rather than "Skip". */
    val stepDone: Boolean
        get() = when (step) {
            SetupStep.SERVER, SetupStep.MICROPHONE -> canContinue
            SetupStep.NOTIFICATIONS -> permissions?.notificationsGranted == true
            SetupStep.ACCESSIBILITY -> permissions?.accessibilityEnabled == true
            SetupStep.PRACTICE -> true
        }

    override fun toString(): String = "SetupUiState(step=$step, address=‹${address.length} chars›, " +
        "textAddress=‹${textAddress.length} chars›, key=‹hidden›, addressError=$addressError, " +
        "textAddressError=$textAddressError, keySaved=$keySaved, formError=$formError, connection=$connection, " +
        "permissions=$permissions)"
}

/** The connection check of the server step. */
@Immutable
sealed interface ConnectionState {
    /** Not tested in this visit. */
    data object Idle : ConnectionState

    /** Setup was completed before; the stored configuration stands until it is tested again. */
    data object Saved : ConnectionState

    /** Reading the certificate or probing health. */
    data object Checking : ConnectionState

    /**
     * The address presented a certificate the system does not trust (self-signed, as our gate's); the user compares
     * it with the server's before it is trusted (D25: then pinned, host name not checked).
     *
     * @property fingerprint the presented certificate.
     * @property changed a different certificate was confirmed for this address before.
     * @property address the address that presented it, as typed back.
     */
    data class ConfirmFingerprint(
        val fingerprint: CertificateFingerprint,
        val changed: Boolean,
        val address: String = "",
    ) : ConnectionState

    /**
     * Every address answered and the settings are saved.
     *
     * @property summary which addresses, which checks, which models.
     */
    data class Connected(val summary: ConnectedSummary) : ConnectionState

    /**
     * The test failed.
     *
     * @property message why.
     */
    data class Failed(@param:StringRes val message: Int) : ConnectionState
}

/**
 * What Setup tells the user after a successful test (D60).
 *
 * @property speechAddress the server address.
 * @property speechCheck how it proved speech.
 * @property textAddress the separate text model address, null when the server address serves text.
 * @property textCheck how text was proved.
 * @property gateVersion Telltype Server's version when its health answered, else null.
 * @property cleanupReady false only when Telltype Server reports its cleanup model still loading.
 * @property speechModel the speech model chosen.
 * @property cleanupModel the cleanup model chosen, null for the server's default.
 * @property commandModel the command model chosen, null for the server's default.
 */
@Immutable
data class ConnectedSummary(
    val speechAddress: String,
    val speechCheck: ServerCheckMethod,
    val textAddress: String?,
    val textCheck: ServerCheckMethod,
    val gateVersion: String?,
    val cleanupReady: Boolean,
    val speechModel: String,
    val cleanupModel: String?,
    val commandModel: String?,
)

/** What the user did in setup. */
sealed interface SetupAction {
    /**
     * Typed in the address field.
     *
     * @property value the text.
     */
    data class AddressChanged(val value: String) : SetupAction

    /**
     * Typed in the text model address field.
     *
     * @property value the text.
     */
    data class TextAddressChanged(val value: String) : SetupAction

    /** Opened or closed Advanced. */
    data object ToggleAdvanced : SetupAction

    /**
     * Typed in the key field.
     *
     * @property value the text; never logged.
     */
    data class KeyChanged(val value: String) : SetupAction {
        override fun toString(): String = "KeyChanged(‹hidden›)"
    }

    /** Pressed Test. */
    data object Test : SetupAction

    /** The fingerprint matches the server's. */
    data object ConfirmFingerprint : SetupAction

    /** The fingerprint does not match. */
    data object RejectFingerprint : SetupAction

    /**
     * Pressed a grant button.
     *
     * @property target the permission or switch.
     */
    data class Grant(val target: FixTarget) : SetupAction

    /** Next (or Skip). */
    data object Next : SetupAction

    /** Back to the previous step. */
    data object Back : SetupAction

    /** The screen came to the front: re-read every permission. */
    data object Resumed : SetupAction
}

/** One-off effects of setup. */
sealed interface SetupEffect {
    /** Setup is finished: go Home. */
    data object Finished : SetupEffect
}
