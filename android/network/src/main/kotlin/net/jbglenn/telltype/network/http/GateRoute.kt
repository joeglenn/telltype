package net.jbglenn.telltype.network.http

/** The routes the app uses: the OpenAI-compatible ones every server has, and our gate's health (Appendix C). */
internal enum class GateRoute(val path: String) {
    /** `GET /healthz`: our gate only. */
    HEALTH("/healthz"),

    /** `GET /v1/models`: reachability and the model lists (D60). */
    MODELS("/v1/models"),

    /** `POST /v1/audio/transcriptions`. */
    TRANSCRIPTION("/v1/audio/transcriptions"),

    /** `POST /v1/chat/completions`. */
    CHAT("/v1/chat/completions"),
}
