package net.jbglenn.telltype.core.common

import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId

/**
 * Creates the ids the app assigns itself. Injected so tests get predictable ids. Production ids are random
 * UUIDs, unique across process restarts. Database row ids (dictionary terms, replacements, snippets) are not
 * made here: the repository assigns them on insert.
 */
public interface IdGenerator {
    /** A new id for one dictation session; it is also sent to the server as `X-Request-Id`. */
    public fun newSessionId(): SessionId

    /** A new id for one recording file. */
    public fun newRecordingId(): RecordingId
}
