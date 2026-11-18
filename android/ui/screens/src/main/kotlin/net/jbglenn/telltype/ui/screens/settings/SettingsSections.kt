package net.jbglenn.telltype.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.component.ChoiceOptions
import net.jbglenn.telltype.ui.designsystem.component.ChoiceRow
import net.jbglenn.telltype.ui.designsystem.component.InfoRow
import net.jbglenn.telltype.ui.designsystem.component.LinkRow
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.designsystem.component.SwitchRow
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.formatFingerprint
import net.jbglenn.telltype.ui.screens.setup.displayText

/** Which choice row is open, and how to open or close one by key. */
internal typealias ExpandToggle = (String) -> (Boolean) -> Unit

/** Server: the address and the text model address (both to setup), the key, how the certificate is trusted. */
@Composable
internal fun ServerSection(settings: Settings, onNavigate: (SettingsDestination) -> Unit) {
    val server = settings.server
    Column {
        SectionHeader(text = stringResource(R.string.settings_server))
        LinkRow(
            title = stringResource(R.string.settings_server_addresses),
            supporting = server.address?.displayText() ?: stringResource(R.string.settings_not_set_up),
            icon = TelltypeIcons.Dns,
            onClick = { onNavigate(SettingsDestination.SERVER) },
        )
        if (server.address != null) {
            LinkRow(
                title = stringResource(R.string.settings_server_text_address),
                supporting = server.textAddress?.displayText() ?: stringResource(R.string.settings_same_address),
                icon = TelltypeIcons.Dns,
                onClick = { onNavigate(SettingsDestination.SERVER) },
            )
        }
        InfoRow(
            title = stringResource(R.string.settings_server_key),
            value = stringResource(if (server.key == null) R.string.settings_key_none else R.string.settings_key_saved),
            icon = TelltypeIcons.Key,
        )
        InfoRow(
            title = stringResource(R.string.settings_server_certificate),
            value = trustText(server.address, server.certificate),
            icon = TelltypeIcons.VerifiedUser,
        )
    }
}

/** How [address]'s certificate is trusted: its pinned fingerprint, the system's trust, or none (HTTP). */
@Composable
private fun trustText(address: ServerEndpoint?, pin: CertificateFingerprint?): String = when {
    address == null -> stringResource(R.string.settings_not_set_up)
    pin != null -> formatFingerprint(pin, short = true)
    address.isPlainHttp -> stringResource(R.string.settings_certificate_none_http)
    else -> stringResource(R.string.settings_certificate_system)
}

/** Bubble: shown, side, look (colour, transparency, microphone glyph), haptics, tones. */
@Composable
internal fun BubbleSection(
    settings: Settings,
    onAction: (SettingsAction) -> Unit,
    expanded: String?,
    toggle: ExpandToggle,
) {
    val bubble = settings.bubble
    Column {
        SectionHeader(text = stringResource(R.string.settings_bubble))
        SwitchRow(
            title = stringResource(R.string.settings_bubble_enabled),
            checked = bubble.enabled,
            onCheckedChange = { onAction(SettingsAction.BubbleEnabled(it)) },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_bubble_side),
            options = ChoiceOptions(SettingsChoices.Sides.map { stringResource(it.label) }),
            selectedIndex = SettingsChoices.Sides.indexOf(bubble.side),
            expanded = expanded == "side",
            onExpandedChange = toggle("side"),
            onSelect = { onAction(SettingsAction.BubbleSideChanged(SettingsChoices.Sides[it])) },
        )
        BubbleLookRows(look = bubble.look, onChange = { onAction(SettingsAction.BubbleLookChanged(it)) })
        SwitchRow(
            title = stringResource(R.string.settings_haptics),
            checked = bubble.haptics,
            onCheckedChange = { onAction(SettingsAction.Haptics(it)) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_sound_cues),
            checked = bubble.soundCues,
            onCheckedChange = { onAction(SettingsAction.SoundCues(it)) },
        )
    }
}

/** Dictation: session limit, pause other audio, context around the cursor. */
@Composable
internal fun DictationSection(
    settings: Settings,
    onAction: (SettingsAction) -> Unit,
    expanded: String?,
    toggle: ExpandToggle,
) {
    Column {
        SectionHeader(text = stringResource(R.string.settings_dictation))
        ChoiceRow(
            title = stringResource(R.string.settings_session_limit),
            options = ChoiceOptions(
                SettingsChoices.SessionLimits.map {
                    pluralStringResource(
                        R.plurals.settings_minutes,
                        it.inWholeMinutes.toInt(),
                        it.inWholeMinutes.toInt(),
                    )
                },
            ),
            selectedIndex = nearestChoice(SettingsChoices.SessionLimits, settings.dictation.sessionLimit),
            expanded = expanded == "limit",
            onExpandedChange = toggle("limit"),
            onSelect = { onAction(SettingsAction.SessionLimit(SettingsChoices.SessionLimits[it])) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_pause_audio),
            supporting = stringResource(R.string.settings_pause_audio_body),
            checked = settings.dictation.pauseOtherAudio,
            onCheckedChange = { onAction(SettingsAction.PauseOtherAudio(it)) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_context),
            supporting = stringResource(
                R.string.settings_context_body,
                settings.context.charsBefore,
                settings.context.charsAfter,
            ),
            checked = settings.context.readAroundCursor,
            onCheckedChange = { onAction(SettingsAction.ReadAroundCursor(it)) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_never_clipboard),
            supporting = stringResource(R.string.settings_never_clipboard_body),
            checked = settings.insertion.neverUseClipboard,
            onCheckedChange = { onAction(SettingsAction.NeverUseClipboard(it)) },
        )
    }
}

private val BubbleSide.label: Int
    get() = when (this) {
        BubbleSide.LEFT -> R.string.settings_side_left
        BubbleSide.RIGHT -> R.string.settings_side_right
    }
