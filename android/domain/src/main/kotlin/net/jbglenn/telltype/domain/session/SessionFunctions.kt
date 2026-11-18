package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Transition
import net.jbglenn.telltype.core.model.settings.BubbleSettings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.system.PermissionStatus

/**
 * The session state machine (docs/PLAN.md sections 6.4 and 6.5). Pure: no I/O, no clock, no Android, no ids made
 * here. The coordinator runs the returned effects in order and feeds their results back as events. Total: an event
 * that does not apply to [state] returns `Transition(state, emptyList())`.
 *
 * Rules it enforces (the full transition table is in `android/docs/contracts.md`):
 * - One session at a time. A press during Working is ignored with `ShowChip(Chip.Busy)`.
 * - The target field is captured at record start; the inserter refuses any other field, and a refused insertion
 *   becomes a hand-off (Paste chip, History).
 * - Leaving the field or closing the keyboard during Arming, Listening or Working does not stop the session;
 *   the in-session state only records the new [Focus]. When the session ends, the bubble goes to
 *   `focus.idleState()`.
 * - `Interrupted` (screen off, call, microphone lost) and `SESSION_LIMIT` end Listening and move to Working.
 * - Errors follow the audio policy on `DictationError`; dictation never fails on cleanup (rule-based fallback).
 * - Every timer it schedules is cancelled when the state that owns it is left.
 * - No `SaveHistory` for incognito fields; results of another session (`StageResult.session`) are ignored.
 * - A `RecordingStarted` outside Arming is answered with `CancelRecording` (unless it repeats the recording the
 *   state already owns).
 */
// One branch per event type keeps the dispatch exhaustive; each branch is a single call.
@Suppress("CyclomaticComplexMethod")
public fun reduce(state: SessionState, event: SessionEvent): Transition = when (event) {
    is SessionEvent.FocusChanged -> onFocusChanged(state, event.focus)
    SessionEvent.MicPressed -> onMicPressed(state)
    SessionEvent.HoldDetected -> onHoldDetected(state)
    SessionEvent.MicReleased -> onMicReleased(state)
    SessionEvent.DragStarted -> onDragStarted(state)
    SessionEvent.FinishTapped -> onFinishTapped(state)
    SessionEvent.CancelTapped -> onCancelTapped(state)
    is SessionEvent.ActionTapped -> onActionTapped(state, event.action)
    is SessionEvent.RecordingStarted -> onRecordingStarted(state, event)
    is SessionEvent.RecordingFailed -> onRecordingFailed(state, event.error)
    is SessionEvent.Interrupted -> onInterrupted(state)
    is SessionEvent.TimerFired -> onTimerFired(state, event.timer)
    is SessionEvent.CorrectionDetected -> onCorrectionDetected(state, event.correction)
    is SessionEvent.LearnAccepted -> onLearnAccepted(state, event.correction)
    is SessionEvent.StageResult -> onStageResult(state, event)
}

/**
 * Everything [evaluateFocus] decides from.
 *
 * @property snapshot the focused field, null when none.
 * @property appRule the rule for the snapshot's app, null when it has none.
 * @property bubble the bubble settings (enabled).
 * @property snoozed the user snoozed the bubble (for an hour or for this app) and the snooze has not ended.
 * @property permissions the permission snapshot.
 * @property serverConfigured `ServerConfig.isConfigured`, or an on-device engine is installed.
 */
public data class FocusInput(
    public val snapshot: FieldSnapshot?,
    public val appRule: AppRule?,
    public val bubble: BubbleSettings,
    public val snoozed: Boolean,
    public val permissions: PermissionStatus,
    public val serverConfigured: Boolean,
)

/**
 * What the bubble may do on the focused field (docs/PLAN.md sections 4.1 and 7.5): [Focus.None] with no field, with
 * the keyboard hidden, for password and numeric fields, in excluded apps, when the bubble is off or snoozed;
 * [Focus.Blocked] when the microphone permission is missing or no server is configured; otherwise
 * [Focus.Available] in command mode when text is selected, dictation mode when not. Also [Focus.None] when the
 * accessibility service is off. The app rule counts only when it is for the snapshot's app. The checks run in that
 * order, so a password field in an app without microphone permission shows no bubble at all.
 */
public fun evaluateFocus(input: FocusInput): Focus = focusFor(input)

/**
 * The category of [app]: the rule's override if [rule] has one, else the shipped seed map (docs/PLAN.md Appendix
 * J, kept in `:domain`), else [AppCategory.OTHER]. A rule for another app is ignored.
 */
public fun resolveCategory(app: AppPackage, rule: AppRule?): AppCategory = categoryOf(app, rule)
