package app.lector.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import app.lector.core.TextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A document loaded from a file (extension already stripped from [title]; [format] kept). */
data class ImportedDoc(val title: String, val text: String, val format: String)

/** One openable entry discovered in a folder scan. */
data class LibraryItem(val name: String, val uri: Uri)

/** Result of importing a document, with a user-facing message on failure. */
sealed interface ImportResult {
    data class Ok(val doc: ImportedDoc) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/**
 * Bridges Android's Storage Access Framework to text extraction. Uses SAF
 * (content:// URIs) throughout, so Lector needs NO storage permission — the user
 * grants access per-file or per-folder through the system picker. A persisted
 * folder (tree) grant also covers the files inside it, so we store many file URIs
 * against a handful of folder grants; directly-opened single files get their own
 * persisted grant so they survive a reboot.
 */
class DocumentImporter(context: Context) {

    private val appContext = context.applicationContext
    private val pdf by lazy { PdfExtractor(appContext) }

    fun isSupported(name: String): Boolean =
        TextExtractor.isSupported(name) || extensionOf(name) == "pdf"

    /** Read + extract a single document, classifying failures for the UI. */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "Document"
        val ext = extensionOf(name)

        val size = fileSize(uri)
        if (size != null && size > MAX_BYTES) {
            return@withContext ImportResult.Failed(
                "This file is too large to read on-device (${size / (1024 * 1024)} MB, limit ${MAX_BYTES / (1024 * 1024)} MB).",
            )
        }

        val stream = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            return@withContext ImportResult.Failed(
                "Access to this file was lost. Reopen it from its folder or reselect it.",
            )
        } ?: return@withContext ImportResult.Failed("Couldn’t open that file.")

        val text = stream.use { s ->
            runCatching {
                when {
                    ext == "pdf" -> pdf.extract(s)
                    TextExtractor.isSupported(name) -> TextExtractor.extract(name, s.readBytes())
                    else -> {
                        val bytes = s.readBytes()
                        if (TextExtractor.looksLikeText(bytes)) bytes.toString(Charsets.UTF_8) else null
                    }
                }
            }.getOrNull()
        }

        when {
            text == null -> ImportResult.Failed(
                "Couldn’t read that file. Try TXT, Markdown, EPUB, PDF, or another text file.",
            )
            text.isBlank() && ext == "pdf" -> ImportResult.Failed(
                "This PDF has no selectable text — it looks like a scanned image. Text recognition (OCR) isn’t supported yet.",
            )
            text.isBlank() -> ImportResult.Failed("That file has no readable text.")
            else -> ImportResult.Ok(ImportedDoc(name.substringBeforeLast('.'), text.trim(), ext))
        }
    }

    /** Persist read access to a chosen folder so it (and its files) survive restarts. */
    fun rememberFolder(treeUri: Uri) = persistRead(treeUri)

    /** Persist read access to a directly-opened file so Recents can reopen it later. */
    fun rememberFile(uri: Uri) = persistRead(uri)

    private fun persistRead(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /**
     * Recursively collect supported documents under [treeUri]. Depth-bounded to
     * avoid pathological trees; SAF traversal is slow, so this runs on IO.
     */
    suspend fun scanFolderRecursive(treeUri: Uri): List<LibraryItem> = withContext(Dispatchers.IO) {
        val root = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
            ?: return@withContext emptyList()
        val found = mutableListOf<LibraryItem>()
        val queue = ArrayDeque<Pair<DocumentFile, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            for (child in runCatching { dir.listFiles() }.getOrNull() ?: emptyArray()) {
                when {
                    child.isDirectory && depth < MAX_DEPTH -> queue.add(child to depth + 1)
                    child.isFile && child.name?.let(::isSupported) == true ->
                        child.name?.let { found.add(LibraryItem(it, child.uri)) }
                }
            }
        }
        found.sortedBy { it.name.lowercase() }
    }

    private fun fileSize(uri: Uri): Long? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        return null
    }

    private fun displayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_BYTES = 40L * 1024 * 1024 // 40 MB
    }
}
