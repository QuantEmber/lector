package app.lector.core

/**
 * One speakable sentence within a document.
 *
 * @property index position in reading order (0-based)
 * @property text the sentence text as spoken
 * @property start absolute offset of the first char in the original document
 * @property end absolute offset after the last char in the original document
 * @property endsParagraph true when a paragraph break follows this sentence
 */
data class Sentence(
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val endsParagraph: Boolean = false,
)

/** A segmented document ready for playback. */
data class Document(
    val rawText: String,
    val sentences: List<Sentence>,
) {
    val isEmpty: Boolean get() = sentences.isEmpty()
}
