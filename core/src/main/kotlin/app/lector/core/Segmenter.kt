package app.lector.core

import java.text.BreakIterator
import java.util.Locale

/**
 * Splits raw text into speakable sentences with document-absolute offsets.
 *
 * Pipeline: ICU [BreakIterator] split → abbreviation-merge repair → hard-split
 * of over-long sentences. Offsets stay document-absolute end-to-end so the UI
 * highlights the exact source range while the engine speaks.
 *
 * Why each step exists:
 *  - BreakIterator (UAX-29) is the strong baseline, but it breaks on ". " +
 *    capital, over-splitting "Dr. Smith" / "5 p.m. sharp" into audible stutters.
 *  - The merge step repairs the common cases (DNA Zipper C2): TITLE abbreviations
 *    (Dr., Mr.…) always attach to the following name; CONDITIONAL abbreviations
 *    (etc., p.m., no.…) attach only when the next fragment continues the sentence
 *    (starts lowercase or with a digit). Merges are length-capped so a run of
 *    abbreviations can never fuse a whole document into one sentence.
 *  - The hard-split step guarantees no sentence exceeds [maxSpeakChars]. Android
 *    TextToSpeech silently rejects text longer than getMaxSpeechInputLength()
 *    (4000) — an unpunctuated paste would otherwise be dropped without a sound
 *    (DNA Zipper C1).
 */
class Segmenter(
    private val locale: Locale = Locale.getDefault(),
    /** Upper bound per spoken sentence. Default leaves margin under the 4000 TTS cap. */
    private val maxSpeakChars: Int = 3800,
) {
    private data class Span(val text: String, val start: Int, val end: Int)

    fun segment(rawText: String): Document {
        val raw = rawSpans(rawText)
        val merged = mergeAbbreviations(raw, rawText)
        val bounded = merged.flatMap { splitLong(it, rawText) }

        val sentences = bounded.mapIndexed { i, span ->
            val gapEnd = if (i + 1 < bounded.size) bounded[i + 1].start else rawText.length
            val gap = if (span.end <= gapEnd) rawText.substring(span.end, gapEnd) else ""
            Sentence(
                index = i,
                text = span.text,
                start = span.start,
                end = span.end,
                endsParagraph = i == bounded.lastIndex || gap.count { it == '\n' } >= 2,
            )
        }
        return Document(rawText, sentences)
    }

    private fun rawSpans(rawText: String): List<Span> {
        val spans = mutableListOf<Span>()
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(rawText)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val slice = rawText.substring(start, end)
            val trimmed = slice.trim()
            if (trimmed.isNotEmpty()) {
                val leading = slice.indexOfFirst { !it.isWhitespace() }
                val spanStart = start + leading
                spans += Span(trimmed, spanStart, spanStart + trimmed.length)
            }
            start = end
            end = iterator.next()
        }
        return spans
    }

    private fun mergeAbbreviations(spans: List<Span>, rawText: String): List<Span> {
        if (spans.size < 2) return spans
        val out = mutableListOf<Span>()
        var i = 0
        while (i < spans.size) {
            var current = spans[i]
            while (i + 1 < spans.size && shouldMerge(current.text, spans[i + 1].text) &&
                (spans[i + 1].end - current.start) <= MAX_MERGE_CHARS
            ) {
                val next = spans[i + 1]
                current = Span(rawText.substring(current.start, next.end).trim(), current.start, next.end)
                i++
            }
            out += current
            i++
        }
        return out
    }

    /** True when [current] should not end a sentence given what [next] starts with. */
    private fun shouldMerge(current: String, next: String): Boolean {
        val lastWord = current.substringAfterLast(' ').lowercase()
        if (lastWord in TITLE_ABBREV) return true // Dr. Smith — always attaches to the name
        if (lastWord in CONDITIONAL_ABBREV) {
            val firstChar = next.firstOrNull() ?: return false
            return firstChar.isLowerCase() || firstChar.isDigit() // "5 p.m. sharp" but not "…p.m. We left."
        }
        return false
    }

    /** Break a span longer than [maxSpeakChars] at word boundaries; offsets stay absolute. */
    private fun splitLong(span: Span, rawText: String): List<Span> {
        if (span.text.length <= maxSpeakChars) return listOf(span)
        val out = mutableListOf<Span>()
        var cursor = span.start
        while (cursor < span.end) {
            var chunkEnd = minOf(cursor + maxSpeakChars, span.end)
            if (chunkEnd < span.end) {
                // Back up to the last whitespace so we never split mid-word.
                val lastSpace = rawText.lastIndexOf(' ', chunkEnd - 1)
                if (lastSpace > cursor) chunkEnd = lastSpace
            }
            val text = rawText.substring(cursor, chunkEnd).trim()
            if (text.isNotEmpty()) {
                val leading = rawText.substring(cursor, chunkEnd).indexOfFirst { !it.isWhitespace() }
                val s = cursor + leading.coerceAtLeast(0)
                out += Span(text, s, s + text.length)
            }
            cursor = chunkEnd
            while (cursor < span.end && rawText[cursor].isWhitespace()) cursor++
        }
        return out.ifEmpty { listOf(span) }
    }

    private companion object {
        const val MAX_MERGE_CHARS = 400

        /** Always followed by a capitalized name — safe to always merge. */
        val TITLE_ABBREV = setOf(
            "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "mt.",
            "gen.", "sen.", "rep.", "gov.", "lt.", "col.", "capt.", "sgt.", "rev.",
        )

        /** Merge only when the next fragment continues the sentence (lowercase/digit). */
        val CONDITIONAL_ABBREV = setOf(
            "vs.", "etc.", "e.g.", "i.e.", "a.m.", "p.m.", "no.", "fig.",
            "inc.", "ltd.", "co.", "dept.", "univ.", "assn.", "approx.", "al.",
        )
    }
}
