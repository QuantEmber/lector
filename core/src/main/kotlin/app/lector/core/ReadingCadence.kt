package app.lector.core

/**
 * Pause policy between spoken units. These silences are what make listening
 * comfortable — commercial apps get this wrong by rushing sentence boundaries,
 * which is especially hard on dyslexic and low-vision listeners following along
 * with the highlight.
 *
 * ── OPERATOR-TUNABLE (learning-mode decision) ────────────────────────────────
 * Brian: these defaults are my first estimate. When you user-test v0.1, tune
 * them by feel — the right values are a product judgment, not a technical one:
 *   - interSentencePauseMs: too short = breathless; too long = loses momentum.
 *   - paragraphPauseMs: should feel like a reader taking a breath at a break.
 *   - Both scale inversely with speech rate (faster speech → shorter pauses),
 *     controlled by scaleWithRate(). Override if that feels wrong.
 * ─────────────────────────────────────────────────────────────────────────────
 */
data class ReadingCadence(
    val interSentencePauseMs: Long = 180L,
    val paragraphPauseMs: Long = 550L,
) {
    /** Pause after [sentence], scaled for the current speech [rate] (1.0 = normal). */
    fun pauseAfter(sentence: Sentence, rate: Float): Long {
        val base = if (sentence.endsParagraph) paragraphPauseMs else interSentencePauseMs
        return scaleWithRate(base, rate)
    }

    private fun scaleWithRate(baseMs: Long, rate: Float): Long =
        (baseMs / rate.coerceIn(0.5f, 4.0f)).toLong()
}
