package app.lector.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.lector.core.Document
import app.lector.core.ReadingCadence
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SpeechEngine] backed by Android's system TextToSpeech (ADR-003 compat path).
 *
 * Each sentence is queued as its own utterance ("s:<gen>:<index>") separated by
 * cadence silences, so progress callbacks drive sentence-level highlighting even
 * on engines that never call onRangeStart. Engines that do (Google TTS,
 * SherpaTTS) get word-level karaoke for free.
 *
 * Robustness (DNA Zipper v0.1):
 *  - Generation token in every utterance id (W1): callbacks from a superseded
 *    speak() are dropped, so a straggling onDone can't stomp fresh state.
 *  - Audio focus + ACTION_AUDIO_BECOMING_NOISY (E4): we duck out of others'
 *    audio politely and stop when headphones are unplugged, so a private article
 *    never blares from the speaker.
 *  - INITIALIZING/READY/FAILED status (E2): a missing/failed engine surfaces to
 *    the UI instead of hanging on "Preparing…".
 *  - [finished] fires only on natural completion, distinguishing it from stop().
 *
 * Known v0.1 limitation: Android TTS has no true pause — stop() + re-speak from
 * the current sentence is the resume strategy (handled by the caller).
 */
class SystemTtsEngine(
    context: Context,
    private val cadence: ReadingCadence = ReadingCadence(),
) : SpeechEngine {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("lector_prefs", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(EngineStatus.INITIALIZING)
    private val _speaking = MutableStateFlow(false)
    private val _position = MutableStateFlow<SpeechPosition?>(null)
    private val _finished = MutableStateFlow(false)
    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    private val _selectedVoiceId = MutableStateFlow(prefs.getString(KEY_VOICE, null))

    override val status: StateFlow<EngineStatus> = _status.asStateFlow()
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    override val position: StateFlow<SpeechPosition?> = _position.asStateFlow()
    override val finished: StateFlow<Boolean> = _finished.asStateFlow()
    override val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()
    override val selectedVoiceId: StateFlow<String?> = _selectedVoiceId.asStateFlow()

    // Guards against stale callbacks: only the current generation may mutate state.
    @Volatile private var generation = 0
    @Volatile private var lastQueuedIndex = -1

    // Windowed playback state (fixes the large-document ANR — see CRASH_DIAGNOSIS_v0.3):
    // enqueue only WINDOW sentences ahead and refill one on each onDone, so speak()/
    // seek() cost O(window) binder calls, never O(document).
    //
    // Thread model: speak()/stop() run on the main thread; TTS callbacks arrive on a
    // binder thread. All window mutation (playDoc/nextToEnqueue) is confined to the
    // main looper — onDone only POSTS the refill there — so the read-modify-write is
    // single-threaded and each refill re-validates the utterance's generation before
    // acting (drops callbacks a newer speak()/stop() superseded).
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var playDoc: Document? = null
    private var nextToEnqueue = 0
    @Volatile private var playRate = 1.0f

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { change ->
                    // Lost focus (a call, another player) → stop rather than talk over.
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stop()
                    }
                }
                .build()
        } else {
            null
        }

    // Headphones unplugged → stop, so the article doesn't play aloud on the speaker.
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            loadVoices()
            applySelectedVoice()
            _status.value = EngineStatus.READY
        } else {
            _status.value = EngineStatus.FAILED
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                sentenceIndexOf(utteranceId)?.let { index ->
                    _position.value = SpeechPosition(index)
                    _speaking.value = true
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                sentenceIndexOf(utteranceId)?.let { index ->
                    _position.value = SpeechPosition(index, start, end)
                }
            }

            override fun onDone(utteranceId: String?) {
                val gen = generationOf(utteranceId) ?: return
                val index = sentenceIndexOf(utteranceId) ?: return
                // Hop to the main looper so window mutation stays single-threaded; the
                // captured gen is re-checked there against the current generation.
                if (index == lastQueuedIndex) {
                    mainHandler.post { if (gen == generation) finishNaturally() }
                } else {
                    mainHandler.post { enqueueNext(gen) }
                }
            }

            @Deprecated("Deprecated in API but still invoked by some engines")
            override fun onError(utteranceId: String?) = handleError(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = handleError(utteranceId)
        })
    }

    override fun speak(document: Document, fromSentence: Int, rate: Float) {
        if (_status.value != EngineStatus.READY || document.isEmpty) return
        val sentences = document.sentences
        val from = fromSentence.coerceIn(0, sentences.lastIndex)
        val gen = ++generation // supersede any in-flight queue
        lastQueuedIndex = sentences.lastIndex
        _finished.value = false
        playDoc = document
        playRate = rate

        if (!requestFocus()) return
        registerNoisy()
        applySelectedVoice()
        tts.setSpeechRate(rate)

        // Flush, then enqueue only the initial window. onDone refills the rest.
        // Runs on the main thread (all callers are main); no lock needed since the
        // refill path (enqueueNext) is posted back to this same thread.
        tts.stop()
        val windowEnd = minOf(from + WINDOW, sentences.size)
        for (i in from until windowEnd) enqueueSentence(i, gen)
        nextToEnqueue = windowEnd
        _speaking.value = true
    }

    /**
     * Queue the next un-enqueued sentence (posted from onDone to keep the window
     * full). Runs on the main looper. [gen] is the generation of the callback that
     * triggered this refill; if a newer speak()/stop() has bumped [generation], the
     * refill is dropped so a superseded queue can't corrupt the current one.
     */
    private fun enqueueNext(gen: Int) {
        if (gen != generation) return
        val doc = playDoc ?: return
        val i = nextToEnqueue
        if (i in doc.sentences.indices) {
            enqueueSentence(i, gen)
            nextToEnqueue = i + 1
        }
    }

    private fun enqueueSentence(i: Int, gen: Int) {
        val doc = playDoc ?: return
        val sentence = doc.sentences[i]
        tts.speak(sentence.text, TextToSpeech.QUEUE_ADD, Bundle(), "s:$gen:$i")
        if (i != lastQueuedIndex) {
            tts.playSilentUtterance(
                cadence.pauseAfter(sentence, playRate),
                TextToSpeech.QUEUE_ADD,
                "p:$gen:$i",
            )
        }
    }

    override fun stop() {
        generation++ // invalidate outstanding callbacks (dropped by the gen re-check)
        playDoc = null // no residual document for a stray callback to act on
        tts.stop()
        _speaking.value = false
        abandonFocus()
        unregisterNoisy()
    }

    override fun shutdown() {
        generation++
        tts.stop()
        tts.shutdown()
        abandonFocus()
        unregisterNoisy()
        _status.value = EngineStatus.FAILED
        _speaking.value = false
        _position.value = null
    }

    override fun selectVoice(id: String?) {
        _selectedVoiceId.value = id
        prefs.edit().apply { if (id == null) remove(KEY_VOICE) else putString(KEY_VOICE, id) }.apply()
        applySelectedVoice()
    }

    /** Read installed, offline voices and expose them sorted by language. */
    private fun loadVoices() {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull() ?: emptySet()
        // Build a base label (language + quality) per voice, then disambiguate
        // any collisions with a trailing counter — clearer than raw engine names.
        data class Raw(val id: String, val base: String, val language: String)
        val raws = voices.asSequence()
            .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .filter { !it.isNetworkConnectionRequired } // offline-first (ADR-003)
            .map { voice ->
                val loc = voice.locale
                val place = loc.displayCountry.ifBlank { "" }
                val langLabel = loc.displayLanguage.ifBlank { loc.toLanguageTag() }
                val locale = if (place.isBlank()) langLabel else "$langLabel ($place)"
                val quality = when {
                    voice.quality >= Voice.QUALITY_VERY_HIGH -> " — enhanced"
                    voice.quality >= Voice.QUALITY_HIGH -> " — high quality"
                    else -> ""
                }
                Raw(voice.name, "$locale$quality", langLabel)
            }
            .sortedWith(compareBy({ it.language }, { it.base }, { it.id }))
            .toList()

        val counts = raws.groupingBy { it.base }.eachCount()
        val seen = HashMap<String, Int>()
        _voices.value = raws.map { raw ->
            val label = if (counts.getValue(raw.base) > 1) {
                val n = (seen[raw.base] ?: 0) + 1
                seen[raw.base] = n
                "${raw.base} · $n"
            } else {
                raw.base
            }
            VoiceOption(id = raw.id, label = label, language = raw.language)
        }
    }

    private fun applySelectedVoice() {
        val id = _selectedVoiceId.value ?: return
        val match = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == id }
        if (match != null) {
            runCatching { tts.voice = match }
        } else {
            // Selected voice is gone (uninstalled) — fall back to default and forget it.
            _selectedVoiceId.value = null
            prefs.edit().remove(KEY_VOICE).apply()
        }
    }

    private fun finishNaturally() {
        _speaking.value = false
        _position.value = null
        _finished.value = true
        abandonFocus()
        unregisterNoisy()
    }

    private fun handleError(utteranceId: String?) {
        // Any error on the current generation ends playback cleanly (W2): no
        // stuck "Pause". Treat as a stop rather than a silent hang.
        if (sentenceIndexOfAnyType(utteranceId) != null) {
            _speaking.value = false
            abandonFocus()
            unregisterNoisy()
        }
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun registerNoisy() {
        if (!noisyReceiverRegistered) {
            appContext.registerReceiver(
                noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisy() {
        if (noisyReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(noisyReceiver) }
            noisyReceiverRegistered = false
        }
    }

    /** "s:<gen>:<index>" for the CURRENT generation → index; else null. */
    private fun sentenceIndexOf(utteranceId: String?): Int? {
        val parts = utteranceId?.split(":") ?: return null
        if (parts.size != 3 || parts[0] != "s") return null
        if (parts[1].toIntOrNull() != generation) return null // stale generation
        return parts[2].toIntOrNull()
    }

    /** The generation embedded in a sentence utterance id ("s:<gen>:<index>"), or null. */
    private fun generationOf(utteranceId: String?): Int? {
        val parts = utteranceId?.split(":") ?: return null
        if (parts.size != 3 || parts[0] != "s") return null
        return parts[1].toIntOrNull()
    }

    /** Like [sentenceIndexOf] but ignores prefix type — used for error bookkeeping. */
    private fun sentenceIndexOfAnyType(utteranceId: String?): Int? {
        val parts = utteranceId?.split(":") ?: return null
        if (parts.size != 3) return null
        if (parts[1].toIntOrNull() != generation) return null
        return parts[2].toIntOrNull()
    }

    private companion object {
        const val KEY_VOICE = "lector.selectedVoiceId"

        // Sentences queued ahead of the one playing. Small enough that seek/play
        // stay instant on huge books; large enough to hide refill latency.
        const val WINDOW = 6
    }
}
