package app.lector.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts plain, speakable text from a supported document, dispatching by file
 * name. Pure JVM (no Android): resolving a content:// URI to bytes is the app
 * layer's job — this operates on the bytes, so it stays unit-testable.
 *
 * Supported v0.2: .txt/.md/.text and .epub. PDF is deferred (needs a text-
 * extraction library — PdfRenderer only rasterizes). MOBI/AZW are proprietary
 * and out of scope.
 */
object TextExtractor {

    /** Formats this pure-JVM extractor handles directly. PDF is added by the app layer. */
    val supportedExtensions = setOf(
        "txt", "text", "md", "markdown", "epub",
        "htm", "html", "xhtml", "csv", "tsv", "log", "json", "xml", "rtf",
    )

    fun isSupported(fileName: String): Boolean =
        extensionOf(fileName) in supportedExtensions

    fun extract(fileName: String, bytes: ByteArray): String =
        when (extensionOf(fileName)) {
            "epub" -> extractEpub(bytes)
            "htm", "html", "xhtml" -> htmlToText(bytes.toString(Charsets.UTF_8))
            "rtf" -> rtfToText(bytes.toString(Charsets.UTF_8))
            else -> bytes.toString(Charsets.UTF_8) // txt/md/csv/log/json/xml → UTF-8 text
        }.trim()

    /**
     * Heuristic for the "read any machine text file" goal: accept bytes that decode
     * as text (mostly printable, few control/NUL bytes). Lets unknown extensions
     * (.rst, .org, .tex, .ini …) be read while rejecting binaries.
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        // UTF-16/UTF-32 text is ~half NUL bytes; a BOM tells us it's still text.
        if (hasUnicodeBom(bytes)) return true
        val sample = bytes.take(4096)
        var control = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false // NUL without a BOM ⇒ binary
            // Allow tab/newline/carriage-return; count other C0 control chars.
            if (v < 0x09 || (v in 0x0E..0x1F)) control++
        }
        return control < maxOf(1, sample.size / 20) // < 5% suspicious, floor of 1
    }

    private fun hasUnicodeBom(b: ByteArray): Boolean {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) ||       // UTF-8
            (at(0) == 0xFF && at(1) == 0xFE) || (at(0) == 0xFE && at(1) == 0xFF) || // UTF-16
            (at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF)      // UTF-32 BE
    }

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    /** Crude RTF → text: strip control words and groups. Good enough to listen to. */
    private fun rtfToText(rtf: String): String {
        var s = rtf.replace(Regex("""\\'[0-9a-fA-F]{2}"""), "") // hex escapes
        s = s.replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), " ")     // control words
        s = s.replace(Regex("""[{}]"""), " ")
        return s.replace(Regex("""[ \t]+"""), " ")
            .lineSequence().joinToString("\n") { it.trim() }
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    // ── EPUB ─────────────────────────────────────────────────────────────────
    // An EPUB is a ZIP: META-INF/container.xml points to the OPF; the OPF's
    // <spine> lists the reading order of content documents (by manifest id).

    private fun extractEpub(bytes: ByteArray): String {
        val entries = readZip(bytes)
        val opfPath = findOpfPath(entries["META-INF/container.xml"]) ?: return ""
        val opfXml = entries[opfPath]?.toString(Charsets.UTF_8) ?: return ""
        val opfDir = opfPath.substringBeforeLast('/', "")

        val idToHref = manifestHrefs(opfXml)
        val spineOrder = spineIdRefs(opfXml)

        val builder = StringBuilder()
        for (id in spineOrder) {
            val href = idToHref[id] ?: continue
            val fullPath = resolveRelative(opfDir, href).substringBefore('#')
            val doc = entries[fullPath]?.toString(Charsets.UTF_8) ?: continue
            val text = htmlToText(doc)
            if (text.isNotBlank()) {
                builder.append(text).append("\n\n")
            }
        }
        return builder.toString()
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun findOpfPath(containerXml: ByteArray?): String? {
        val xml = containerXml?.toString(Charsets.UTF_8) ?: return null
        return Regex("""full-path\s*=\s*["']([^"']+)["']""")
            .find(xml)?.groupValues?.get(1)
    }

    private fun manifestHrefs(opfXml: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opfXml).forEach { item ->
            val tag = item.value
            val id = Regex("""\bid\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
            if (id != null && href != null) map[id] = decodeEntities(href)
        }
        return map
    }

    private fun spineIdRefs(opfXml: String): List<String> {
        val spine = Regex("""<spine\b[^>]*>(.*?)</spine>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(opfXml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<itemref\b[^>]*\bidref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(spine).map { it.groupValues[1] }.toList()
    }

    /** Join an OPF-relative href onto the OPF directory, normalizing ".." segments. */
    private fun resolveRelative(baseDir: String, href: String): String {
        val stack = ArrayDeque<String>()
        if (baseDir.isNotEmpty()) baseDir.split('/').forEach { stack.addLast(it) }
        for (part in href.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    // ── HTML/XHTML → text ──────────────────────────────────────────────────────

    fun htmlToText(html: String): String {
        var s = html
        // Drop script/style bodies entirely.
        s = s.replace(Regex("""<(script|style)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        // Block-level tags become paragraph/line breaks so sentences don't fuse.
        s = s.replace(Regex("""</(p|div|h[1-6]|li|br|tr|section|article|blockquote)\s*>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<br\b[^>]*/?>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<(p|div|h[1-6]|li|section|article|blockquote)\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        // Remove all remaining tags.
        s = s.replace(Regex("""<[^>]+>"""), "")
        s = decodeEntities(s)
        // Collapse runs of spaces/tabs; keep paragraph breaks; trim each line.
        s = s.replace(Regex("""[ \t]+"""), " ")
        s = s.lineSequence().joinToString("\n") { it.trim() }
        s = s.replace(Regex("""\n{3,}"""), "\n\n")
        return s.trim()
    }

    private fun decodeEntities(text: String): String =
        text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")

    /** Convenience for stream callers. */
    fun extract(fileName: String, input: InputStream): String =
        extract(fileName, input.readBytes())
}
