package app.lector.speech

import app.lector.core.Document
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the highlight currently is. [wordStart]/[wordEnd] are offsets *within*
 * the sentence text (map to document offsets via Sentence.start); -1 = engine
 * gave no word-level callbacks (varies by system engine — degrade to
 * sentence-level highlighting).
 */
data class SpeechPosition(
    val sentenceIndex: Int,
    val wordStart: Int = -1,
    val wordEnd: Int = -1,
)

/** High-level engine status the UI can react to without knowing the implementation. */
enum class EngineStatus { INITIALIZING, READY, FAILED }

/** A selectable voice. [id] is the engine-stable identifier; [label] is human-facing. */
data class VoiceOption(
    val id: String,
    val label: String,
    val language: String,
)

/**
 * Engine abstraction (ADR-003): SystemTtsEngine today, embedded sherpa-onnx
 * tomorrow — the reader must not care which is speaking.
 */
interface SpeechEngine {
    val status: StateFlow<EngineStatus>
    val speaking: StateFlow<Boolean>
    val position: StateFlow<SpeechPosition?>

    /** Emits true once when a document finishes speaking naturally (not on stop()). */
    val finished: StateFlow<Boolean>

    /** Offline voices the user can choose from (populated once the engine is ready). */
    val voices: StateFlow<List<VoiceOption>>

    /** Currently selected voice id, or null for the engine default. */
    val selectedVoiceId: StateFlow<String?>

    /** Choose a voice by [VoiceOption.id]; null restores the engine default. Persisted. */
    fun selectVoice(id: String?)

    /** Start speaking [document] from [fromSentence] at [rate] (1.0 = normal). */
    fun speak(document: Document, fromSentence: Int, rate: Float)

    /** Stop speech immediately. The caller retains the resume position. */
    fun stop()

    /** Release all engine resources. The instance is unusable afterwards. */
    fun shutdown()
}
