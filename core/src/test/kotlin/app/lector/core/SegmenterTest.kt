package app.lector.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {

    private val segmenter = Segmenter(Locale.US)

    @Test
    fun `splits simple sentences with correct offsets`() {
        val text = "Hello world. This is Lector."
        val doc = segmenter.segment(text)
        assertEquals(2, doc.sentences.size)
        assertEquals("Hello world.", doc.sentences[0].text)
        assertEquals("This is Lector.", doc.sentences[1].text)
        // Offsets must map back into the original text exactly.
        doc.sentences.forEach { s ->
            assertEquals(s.text, text.substring(s.start, s.end))
        }
    }

    @Test
    fun `handles common abbreviations without over-splitting`() {
        val doc = segmenter.segment("Dr. Smith arrived at 5 p.m. sharp. Everyone stood.")
        assertEquals(2, doc.sentences.size)
        assertEquals("Dr. Smith arrived at 5 p.m. sharp.", doc.sentences[0].text)
        assertEquals("Everyone stood.", doc.sentences[1].text)
        // Merged offsets must still map back into the source exactly.
        doc.sentences.forEach { s -> assertEquals(s.text, doc.rawText.substring(s.start, s.end)) }
    }

    @Test
    fun `conditional abbreviation does not merge across a real sentence boundary`() {
        // "p.m." followed by a capitalized new sentence must NOT merge.
        val doc = segmenter.segment("We left at 5 p.m. We were late.")
        assertEquals(2, doc.sentences.size)
        assertEquals("We left at 5 p.m.", doc.sentences[0].text)
        assertEquals("We were late.", doc.sentences[1].text)
    }

    @Test
    fun `no dot merges only when a number follows`() {
        val doc = segmenter.segment("Meeting in room no. 5 today. Bring notes.")
        assertEquals(2, doc.sentences.size)
        assertEquals("Meeting in room no. 5 today.", doc.sentences[0].text)
    }

    @Test
    fun `sentence-ending capital letter is not swallowed by the next`() {
        // Regression guard (DNA Zipper C2): no single-initial over-merge.
        val doc = segmenter.segment("My favorite grade is A. It feels great.")
        assertEquals(2, doc.sentences.size)
        assertEquals("It feels great.", doc.sentences[1].text)
    }

    @Test
    fun `over-long unpunctuated text is hard-split under the speak limit`() {
        val word = "lorem "
        val huge = word.repeat(1200).trim() // ~7200 chars, no sentence terminators
        val doc = Segmenter(Locale.US, maxSpeakChars = 3800).segment(huge)
        assertTrue("expected multiple chunks", doc.sentences.size >= 2)
        doc.sentences.forEach { s ->
            assertTrue("chunk over limit: ${s.text.length}", s.text.length <= 3800)
            assertEquals(s.text, huge.substring(s.start, s.end)) // offsets still exact
        }
    }

    @Test
    fun `detects paragraph breaks on blank lines`() {
        val doc = segmenter.segment("First para. Still first.\n\nSecond para begins.")
        assertEquals(3, doc.sentences.size)
        assertEquals(false, doc.sentences[0].endsParagraph)
        assertEquals(true, doc.sentences[1].endsParagraph)
        assertEquals(true, doc.sentences[2].endsParagraph) // last sentence always ends its paragraph
    }

    @Test
    fun `empty and whitespace input yields empty document`() {
        assertTrue(segmenter.segment("").isEmpty)
        assertTrue(segmenter.segment("  \n\n  ").isEmpty)
    }

    @Test
    fun `cadence pauses scale with rate and paragraph breaks`() {
        val cadence = ReadingCadence()
        val mid = Sentence(0, "x", 0, 1, endsParagraph = false)
        val para = Sentence(1, "y", 2, 3, endsParagraph = true)
        assertTrue(cadence.pauseAfter(para, 1.0f) > cadence.pauseAfter(mid, 1.0f))
        assertTrue(cadence.pauseAfter(mid, 2.0f) < cadence.pauseAfter(mid, 1.0f))
    }
}
