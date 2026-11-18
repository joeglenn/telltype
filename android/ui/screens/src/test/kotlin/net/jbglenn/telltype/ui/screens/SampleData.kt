package net.jbglenn.telltype.ui.screens

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.DictionaryTermId
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.settings.BackendHealth
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.OfferedModels
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerHealth
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.settings.ServerStatus
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.screens.settings.ModelOption
import net.jbglenn.telltype.ui.screens.settings.ModelsUiState
import net.jbglenn.telltype.ui.screens.setup.ConnectedSummary

/** Fixed, synthetic data for the screenshots and UI tests. Never real dictation. */
object SampleData {
    val Now = Fixtures.Epoch

    val Fingerprint = CertificateFingerprint("3f9ac0127be4d5a86190cc3e7f42b8d01a6e95c4f2d7380be1a4c69f5d20e7b3")

    val ConfiguredSettings: Settings = Settings.Defaults.copy(
        server = ServerConfig(
            address = ServerEndpoint("192.0.2.10", 8170),
            key = ServerKey("sample-key"),
            certificate = Fingerprint,
        ),
    )

    /** A stock setup (D60): plain HTTP speech and text servers on the home network, no key. */
    val StockSettings: Settings = Settings.Defaults.copy(
        server = ServerConfig(
            address = ServerEndpoint("192.168.1.20", 18201, Scheme.HTTP),
            key = null,
            certificate = null,
            textAddress = ServerEndpoint("192.168.1.20", 18200, Scheme.HTTP),
            models = ServerModels(speech = null, cleanup = "qwen3.5-4b", command = "gemma-3-1b"),
        ),
    )

    val GateConnected = ConnectedSummary(
        speechAddress = "192.0.2.10:8170",
        speechCheck = ServerCheckMethod.GATE_HEALTH,
        textAddress = null,
        textCheck = ServerCheckMethod.GATE_HEALTH,
        gateVersion = "1.2.0",
        cleanupReady = true,
        speechModel = "whisper-1",
        cleanupModel = "cleanup",
        commandModel = "assistant",
    )

    val StockConnected = ConnectedSummary(
        speechAddress = "http://192.168.1.20:18201",
        speechCheck = ServerCheckMethod.SILENT_TRANSCRIPTION,
        textAddress = "http://192.168.1.20:18200",
        textCheck = ServerCheckMethod.MODELS,
        gateVersion = null,
        cleanupReady = true,
        speechModel = "whisper-1",
        cleanupModel = "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
        commandModel = "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
    )

    /** Settings → Models against Telltype Server: real model names, Gemma 4 E4B recommended for cleanup. */
    val Models = ModelsUiState(
        configured = true,
        chosen = ServerModels(cleanup = "gemma-4-e4b-it-qat-q4_0", command = "qwen_qwen3.5-4b-q4_k_m"),
        cleanup = listOf(
            ModelOption("gemma-4-e4b-it-qat-q4_0", recommended = true),
            ModelOption("qwen_qwen3.5-4b-q4_k_m", recommended = false),
        ),
        command = listOf(
            ModelOption("gemma-4-e4b-it-qat-q4_0", recommended = false),
            ModelOption("qwen_qwen3.5-4b-q4_k_m", recommended = true),
        ),
    )

    /** Settings → Models against stock servers: three recommended, one choice no longer listed, typed speech. */
    val StockModels = ModelsUiState(
        configured = true,
        chosen = ServerModels(cleanup = "qwen3.5-4b", command = "gemma-3-1b"),
        cleanup = listOf(
            ModelOption("qwen3.5-2b", recommended = false),
            ModelOption("qwen3.5-4b", recommended = false),
            ModelOption("gemma-4-e4b-it", recommended = true),
            ModelOption("/models/gemma-4-12b-it-qat.gguf", recommended = false),
        ),
        command = listOf(
            ModelOption("qwen3.5-2b", recommended = false),
            ModelOption("qwen3.5-4b", recommended = true),
            ModelOption("gemma-4-e4b-it", recommended = false),
            ModelOption("/models/gemma-4-12b-it-qat.gguf", recommended = true),
        ),
    )

    val Timings = SessionTimings(
        recording = 6.2.seconds,
        finalising = 40.milliseconds,
        transcription = 610.milliseconds,
        cleanup = 880.milliseconds,
        insertion = 35.milliseconds,
        endToEnd = 1.62.seconds,
        server = emptyList(),
    )

    val Entries: List<HistoryEntry> = listOf(
        entry("s1", 12.minutes, AppCategory.PERSONAL_CHAT, "Running ten minutes late, start without me."),
        entry(
            "s2",
            1.hours,
            AppCategory.EMAIL,
            "Thanks for the update. I will review the draft by Friday and send comments.",
        ),
        entry("s3", 3.hours, AppCategory.NOTES_DOCUMENTS, "Buy batteries, call the garage about the tyres."),
        entry("s4", 26.hours, AppCategory.WORK_CHAT, "", errorCode = "server_unreachable"),
        entry("s5", 50.hours, AppCategory.CODE, "kubectl get pods", raw = "cube control get pods"),
    )

    val Terms: List<DictionaryTerm> = listOf(
        DictionaryTerm(DictionaryTermId(1), "Oslo", listOf("oz lo"), EntrySource.MANUAL, uses = 14, createdAt = Now),
        DictionaryTerm(DictionaryTermId(2), "Tailscale", emptyList(), EntrySource.MANUAL, uses = 3, createdAt = Now),
        DictionaryTerm(
            DictionaryTermId(3),
            "Nextcloud",
            listOf("next cloud", "next clod"),
            EntrySource.LEARNED,
            uses = 0,
            createdAt = Now,
        ),
    )

    val Replacements: List<Replacement> = listOf(
        Replacement(ReplacementId(4), "cube control", "kubectl", EntrySource.LEARNED, uses = 6, createdAt = Now),
        Replacement(ReplacementId(5), "get hub", "GitHub", EntrySource.MANUAL, uses = 2, createdAt = Now),
    )

    val Snippets: List<Snippet> = listOf(
        Snippet(SnippetId(1), "my address", "1 Example Street, Exampletown", uses = 9, createdAt = Now),
        Snippet(SnippetId(2), "sign off", "Best regards,\nSam", uses = 21, createdAt = Now),
    )

    val Rules: List<AppRule> = listOf(
        AppRule(AppPackage("com.example.notes"), AppCategory.NOTES_DOCUMENTS, Tone.FORMAL, excluded = false),
        AppRule(AppPackage("com.x8bit.bitwarden"), null, null, excluded = true),
        AppRule(AppPackage("com.example.bank"), null, null, excluded = true),
    )

    val Reachable = ServerReachability.Reachable(
        endpoint = ServerEndpoint("192.0.2.10", 8170),
        health = ServerHealth(
            status = ServerStatus.OK,
            version = "1.2.0",
            stt = BackendHealth(ready = true, model = "parakeet-tdt-0.6b", engine = "nemo"),
            cleanup = BackendHealth(ready = true, model = "qwen3-4b", engine = "llama.cpp"),
            assistant = BackendHealth(ready = false, model = "qwen3-14b", engine = "llama.cpp"),
        ),
        roundTrip = 38.milliseconds,
        checkedAt = Now,
        offered = OfferedModels(speech = null, text = listOf(OfferedModel("qwen3-4b"), OfferedModel("qwen3-14b"))),
    )

    private fun entry(
        id: String,
        age: Duration,
        category: AppCategory,
        finalText: String,
        raw: String = finalText.lowercase().filter { it.isLetterOrDigit() || it == ' ' },
        errorCode: String? = null,
    ) = HistoryEntry(
        id = SessionId(id),
        createdAt = Now - age,
        app = AppPackage("org.example.app"),
        category = category,
        mode = DictationMode.DICTATE,
        finalText = finalText,
        rawTranscript = raw,
        cleanupStatus = if (errorCode == null) CleanupStatus.APPLIED else null,
        insertedVia = if (errorCode == null) InsertedVia.INPUT_CONNECTION else InsertedVia.NONE,
        audioDuration = 6.seconds,
        timings = if (errorCode == null) Timings else SessionTimings.None,
        errorCode = errorCode,
    )
}
