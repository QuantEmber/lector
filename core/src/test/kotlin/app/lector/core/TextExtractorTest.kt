package app.lector.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextExtractorTest {

    @Test
    fun `plain text passes through`() {
        val text = "Hello world.\nSecond line."
        assertEquals(text, TextExtractor.extract("note.txt", text.toByteArray()))
    }

    @Test
    fun `supported extensions detected case-insensitively`() {
        assertTrue(TextExtractor.isSupported("Book.EPUB"))
        assertTrue(TextExtractor.isSupported("readme.md"))
        assertTrue(TextExtractor.isSupported("page.HTML"))
        assertTrue(TextExtractor.isSupported("data.csv"))
        assertFalse("PDF handled by the app layer, not core", TextExtractor.isSupported("scan.pdf"))
        assertFalse(TextExtractor.isSupported("archive.zip"))
    }

    @Test
    fun `html file extension is stripped to text`() {
        val text = TextExtractor.extract("page.html", "<p>Hello <b>there</b>.</p>".toByteArray())
        assertEquals("Hello there.", text)
    }

    @Test
    fun `looksLikeText accepts prose and rejects binary`() {
        assertTrue(TextExtractor.looksLikeText("Just some words.\nAnother line.".toByteArray()))
        val binary = ByteArray(64) { if (it % 3 == 0) 0 else 200.toByte() } // contains NULs
        assertFalse(TextExtractor.looksLikeText(binary))
        assertFalse(TextExtractor.looksLikeText(ByteArray(0)))
    }

    @Test
    fun `rtf control words are stripped`() {
        val rtf = """{\rtf1\ansi\deff0 Hello \b world\b0 .}"""
        val text = TextExtractor.extract("note.rtf", rtf.toByteArray())
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("world"))
        assertFalse(text.contains("\\rtf"))
    }

    @Test
    fun `html is stripped to readable text with paragraph breaks`() {
        val html = "<html><body><h1>Title</h1><p>First &amp; only.</p>" +
            "<style>.x{color:red}</style><p>Next para.</p></body></html>"
        val text = TextExtractor.htmlToText(html)
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("First & only."))
        assertTrue(text.contains("Next para."))
        assertFalse("style body must be removed", text.contains("color:red"))
        assertFalse("no tags remain", text.contains("<"))
    }

    @Test
    fun `epub is extracted in spine order`() {
        val epub = buildEpub(
            spine = listOf("c2", "c1"), // deliberately not manifest order
            chapters = mapOf(
                "c1" to ("chap1.xhtml" to "<html><body><p>Chapter one body.</p></body></html>"),
                "c2" to ("chap2.xhtml" to "<html><body><p>Chapter two body.</p></body></html>"),
            ),
        )
        val text = TextExtractor.extract("book.epub", epub)
        val idxTwo = text.indexOf("Chapter two body.")
        val idxOne = text.indexOf("Chapter one body.")
        assertTrue("both chapters present", idxOne >= 0 && idxTwo >= 0)
        assertTrue("spine order respected (two before one)", idxTwo < idxOne)
    }

    @Test
    fun `epub resolves hrefs relative to the opf directory`() {
        // OPF lives in OEBPS/, chapter href is relative to it.
        val epub = buildEpub(
            spine = listOf("c1"),
            chapters = mapOf("c1" to ("text/only.xhtml" to "<p>Nested chapter.</p>")),
            opfDir = "OEBPS",
        )
        val text = TextExtractor.extract("book.epub", epub)
        assertTrue(text.contains("Nested chapter."))
    }

    // Builds a minimal valid-enough EPUB zip in memory.
    private fun buildEpub(
        spine: List<String>,
        chapters: Map<String, Pair<String, String>>, // id -> (hrefRelToOpf, xhtml)
        opfDir: String = "",
    ): ByteArray {
        val opfPath = if (opfDir.isEmpty()) "content.opf" else "$opfDir/content.opf"
        val manifest = chapters.entries.joinToString("\n") { (id, hrefDoc) ->
            """<item id="$id" href="${hrefDoc.first}" media-type="application/xhtml+xml"/>"""
        }
        val spineXml = spine.joinToString("\n") { """<itemref idref="$it"/>""" }
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                $manifest
              </manifest>
              <spine>
                $spineXml
              </spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", container)
            put(opfPath, opf)
            chapters.values.forEach { (href, xhtml) ->
                val full = if (opfDir.isEmpty()) href else "$opfDir/$href"
                put(full, xhtml)
            }
        }
        return bos.toByteArray()
    }
}
