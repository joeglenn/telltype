package net.jbglenn.telltype.ui.screens.setup

import androidx.annotation.StringRes
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.settings.ModelRole
import net.jbglenn.telltype.core.model.settings.ServerCheck
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.settings.ModelChoices
import net.jbglenn.telltype.domain.settings.ModelRoles
import net.jbglenn.telltype.ui.designsystem.error.connectionMessageRes
import net.jbglenn.telltype.ui.screens.R

/**
 * The models after a successful Setup (D58, D60): a choice made before for the same addresses stays while the
 * server still lists it (the deprecated `cleanup` and `assistant` names are not listed any more, so they are
 * replaced); otherwise cleanup takes [ModelChoices.defaultCleanup] (the model Telltype Server's `/healthz` reports
 * for cleanup, else Gemma 4 E4B, else the first listed), commands [ModelChoices.defaultCommand] and speech
 * [ModelChoices.defaultSpeech] (null: `whisper-1` is sent). A previous choice counts as listed only in its own
 * role's list ([ModelRoles.forRole]), so a speech model never stays chosen for cleanup.
 */
internal fun chooseModels(previous: ServerModels, speech: ServerCheck, text: ServerCheck): ServerModels {
    val textModels = text.models
    val speechModels = speech.models
    return ServerModels(
        speech = ModelChoices.keepIfListed(
            previous.speech,
            speechModels?.let { ModelRoles.forRole(it, ModelRole.SPEECH) },
            ModelChoices.defaultSpeech(speechModels),
        ),
        cleanup = ModelChoices.keepIfListed(
            previous.cleanup,
            textModels?.let { ModelRoles.forRole(it, ModelRole.CLEANUP) },
            textModels?.let { ModelChoices.defaultCleanup(it, (text.health ?: speech.health)?.cleanup?.model) },
        ),
        command = ModelChoices.keepIfListed(
            previous.command,
            textModels?.let { ModelRoles.forRole(it, ModelRole.COMMANDS) },
            textModels?.let(ModelChoices::defaultCommand),
        ),
    )
}

/** What Setup shows once [server] is saved after [speech] and [text] passed. */
internal fun connectedSummary(server: ServerConfig, speech: ServerCheck, text: ServerCheck): ConnectedSummary =
    ConnectedSummary(
        speechAddress = server.address?.displayText().orEmpty(),
        speechCheck = speech.methods[ServerRole.SPEECH] ?: ServerCheckMethod.MODELS,
        textAddress = server.textAddress?.displayText(),
        textCheck = text.methods[ServerRole.TEXT] ?: ServerCheckMethod.MODELS,
        gateVersion = speech.health?.version,
        cleanupReady = speech.health?.cleanup?.ready ?: true,
        speechModel = server.models.speech ?: ServerModels.DEFAULT_SPEECH,
        cleanupModel = server.models.cleanup,
        commandModel = server.models.command,
    )

/** The message for a failed Setup test. */
@StringRes
internal fun NetworkError.setupMessageRes(): Int = when {
    this is RequestRejected && serverCode == "models_missing" -> R.string.setup_error_no_models
    this == ServerNotConfigured -> R.string.setup_error_address_http
    else -> connectionMessageRes()
}

/** How Setup names a check. */
@StringRes
internal fun ServerCheckMethod.labelRes(): Int = when (this) {
    ServerCheckMethod.MODELS -> R.string.setup_check_models
    ServerCheckMethod.GATE_HEALTH -> R.string.setup_check_gate
    ServerCheckMethod.SILENT_TRANSCRIPTION -> R.string.setup_check_silent
}
