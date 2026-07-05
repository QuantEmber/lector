package app.lector.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One book in the persistent library. [sentenceIndex] is filled from the resume store. */
data class LibraryEntry(
    val uri: String,
    val title: String,
    val format: String,
    val addedAt: Long,
    val lastOpened: Long = 0L,
    val sentenceIndex: Int = 0,
)

/**
 * The persistent library: every document discovered by a folder scan or opened by
 * the user, remembered across launches. The catalog (uri/title/format/times) lives
 * in filesDir/library.json and is rewritten only when a document is added or opened.
 * Per-document RESUME positions live in SharedPreferences (keyed per URI) so the
 * frequent position writes during playback are cheap per-key updates, not full-
 * catalog rewrites (DNA Zipper v0.3 F4), and can be flushed synchronously on exit
 * (F3). Dependency-free (org.json) to keep the build simple and F-Droid-clean.
 */
class LibraryStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "library.json")
    private val prefs = appContext.getSharedPreferences("lector_prefs", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()

    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    /** Load persisted catalog into memory. Mutations await this so an early open can't
     *  run against an empty list or be clobbered when load() lands late (F2). */
    suspend fun load() {
        mutex.withLock {
            if (ready.isCompleted) return
            val parsed = withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext emptyList()
                runCatching {
                    val arr = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        LibraryEntry(
                            uri = o.getString("uri"),
                            title = o.getString("title"),
                            format = o.optString("format"),
                            addedAt = o.optLong("addedAt"),
                            lastOpened = o.optLong("lastOpened"),
                        )
                    }
                }.getOrNull().orEmpty()
            }
            _entries.value = parsed
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()

    /** Merge freshly discovered documents, keeping existing entries' open state. */
    suspend fun addDiscovered(items: List<Triple<String, String, String>>, now: Long) {
        awaitReady()
        mutex.withLock {
            val byUri = _entries.value.associateBy { it.uri }.toMutableMap()
            for ((uri, title, format) in items) {
                if (uri !in byUri) byUri[uri] = LibraryEntry(uri, title, format, addedAt = now)
            }
            commit(byUri.values.toList())
        }
    }

    /** Mark a document opened now (drives Recents), inserting it if new. */
    suspend fun markOpened(uri: String, title: String, format: String, now: Long) {
        awaitReady()
        mutex.withLock {
            val byUri = _entries.value.associateBy { it.uri }.toMutableMap()
            byUri[uri] = byUri[uri]?.copy(lastOpened = now)
                ?: LibraryEntry(uri, title, format, addedAt = now, lastOpened = now)
            commit(byUri.values.toList())
        }
    }

    /** Cheap per-URI resume write. [flush] uses commit() for the on-exit save (F3). */
    fun saveResume(uri: String, sentenceIndex: Int, flush: Boolean = false) {
        val editor = prefs.edit().putInt(resumeKey(uri), sentenceIndex)
        if (flush) editor.commit() else editor.apply()
    }

    fun resumeFor(uri: String): Int = prefs.getInt(resumeKey(uri), 0)

    /** Catalog sorted by title, each entry annotated with its resume position. */
    fun all(): List<LibraryEntry> =
        _entries.value.map { it.copy(sentenceIndex = resumeFor(it.uri)) }
            .sortedBy { it.title.lowercase() }

    fun recents(limit: Int = 12): List<LibraryEntry> =
        _entries.value.filter { it.lastOpened > 0 }
            .sortedByDescending { it.lastOpened }
            .take(limit)
            .map { it.copy(sentenceIndex = resumeFor(it.uri)) }

    /** Rewrite the catalog file (add/open only, never per resume tick). Caller holds [mutex]. */
    private suspend fun commit(list: List<LibraryEntry>) {
        _entries.value = list
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("uri", e.uri)
                        .put("title", e.title)
                        .put("format", e.format)
                        .put("addedAt", e.addedAt)
                        .put("lastOpened", e.lastOpened),
                )
            }
            runCatching { file.writeText(JSONObject().put("entries", arr).toString()) }
        }
    }

    private fun resumeKey(uri: String) = "resume:$uri"
}
