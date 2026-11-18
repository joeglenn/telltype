package net.jbglenn.telltype.platform.system

import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.platform.R

/**
 * What one [Notice] looks like as a notification. Holds dictated text in [privateText]: [toString] prints its
 * length only.
 *
 * @property channel the channel id ([NotificationChannels]).
 * @property id the notification id: one per kind, one per session for results, so a repeat replaces.
 * @property title the title.
 * @property text the message, a plurals resource when [count] is set; also the lock-screen version.
 * @property count the quantity for a plurals [text], null for a plain string.
 * @property privateText dictated text shown only while unlocked and offered with a Copy action, null when none.
 * @property fix the screen a tap opens, null to open the app.
 */
internal data class NoticeSpec(
    val channel: String,
    val id: Int,
    val title: Int,
    val text: Int,
    val count: Int?,
    val privateText: String?,
    val fix: FixTarget?,
) {
    override fun toString(): String = "NoticeSpec(channel=$channel, id=$id, count=$count, " +
        "privateText=${privateText?.length} chars, fix=$fix)"
}

/** Maps each [Notice] to its [NoticeSpec]. Pure. */
internal object NoticeContent {
    private const val ACCESSIBILITY_OFF_ID = 0x1001
    private const val ADVANCED_PROTECTION_ID = 0x1002
    private const val PROBLEM_ID = 0x1003
    private const val RECOVERED_ID = 0x1004
    private const val RESULT_ID_BASE = 0x2000

    /** The notification for [notice]. */
    fun of(notice: Notice): NoticeSpec = when (notice) {
        is Notice.TranscribedLater -> NoticeSpec(
            channel = NotificationChannels.RESULTS,
            id = RESULT_ID_BASE + (notice.session.value.hashCode() and RESULT_ID_MASK),
            title = R.string.notice_transcribed_title,
            text = R.string.notice_transcribed_public,
            count = null,
            privateText = notice.text,
            fix = null,
        )

        is Notice.DictationsRecovered -> NoticeSpec(
            channel = NotificationChannels.RESULTS,
            id = RECOVERED_ID,
            title = R.string.notice_recovered_title,
            text = R.plurals.notice_recovered_text,
            count = notice.count,
            privateText = null,
            fix = null,
        )

        Notice.AccessibilityOff -> problem(
            ACCESSIBILITY_OFF_ID,
            R.string.notice_accessibility_off_title,
            R.string.notice_accessibility_off_text,
            FixTarget.ACCESSIBILITY_SERVICE,
        )

        Notice.AdvancedProtectionOn -> problem(
            ADVANCED_PROTECTION_ID,
            R.string.notice_advanced_protection_title,
            R.string.notice_advanced_protection_text,
            fix = null,
        )

        is Notice.ProblemWhileHidden ->
            problem(PROBLEM_ID, R.string.notice_problem_title, R.string.notice_problem_text, notice.error.fix)
    }

    private fun problem(id: Int, title: Int, text: Int, fix: FixTarget?): NoticeSpec =
        NoticeSpec(NotificationChannels.PROBLEMS, id, title, text, count = null, privateText = null, fix = fix)

    /** Keeps per-session result ids within 0x2000..0x2fff, clear of the fixed ids. */
    private const val RESULT_ID_MASK = 0x0fff
}
