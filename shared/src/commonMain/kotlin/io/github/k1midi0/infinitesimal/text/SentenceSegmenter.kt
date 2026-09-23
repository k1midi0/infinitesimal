package io.github.k1midi0.infinitesimal.text

/**
 * Splits an utterance into sentences.
 *
 * The dialogue layer's unit of delivery is the sentence, not the character and not
 * the whole reply. A consumer can therefore treat each sentence as a complete
 * piece of speech — which is what a future speech synthesiser needs, what emotion
 * tagging needs, and what the task log reads at.
 *
 * The text is expected to be prose. This splitter understands punctuation, not
 * markdown: a code fence or a list can be cut in the middle of what a reader would
 * consider one unit. If the model ever emits structured content through the
 * dialogue channel, this is the place that would have to learn about it.
 */
object SentenceSegmenter {

    private val TERMINATORS = setOf('。', '！', '？', '!', '?', '…', '.')

    /** Characters that belong to the sentence they close, not to the next one. */
    private const val CLOSERS = "”’」』»）)】》〉\"'"

    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0

        while (index < text.length) {
            val character = text[index]

            if (character == '\n') {
                // A line break ends a sentence even without punctuation.
                flush(current, sentences)
                index += 1
                continue
            }

            if (character == '.' && isDecimalPoint(text, index)) {
                current.append(character)
                index += 1
                continue
            }

            if (character in TERMINATORS) {
                // A run of terminators is one boundary, which is what keeps
                // "真的吗？！" and "等等……" from becoming several sentences.
                while (index < text.length && text[index] in TERMINATORS) {
                    current.append(text[index])
                    index += 1
                }
                // Closing quotes and brackets travel with the sentence they close.
                while (index < text.length && text[index] in CLOSERS) {
                    current.append(text[index])
                    index += 1
                }
                flush(current, sentences)
                continue
            }

            current.append(character)
            index += 1
        }

        flush(current, sentences)
        return sentences
    }

    private fun flush(current: StringBuilder, into: MutableList<String>) {
        val sentence = current.toString().trim()
        current.clear()
        if (sentence.isNotEmpty()) into += sentence
    }

    /**
     * A full stop between two digits is part of a number, not the end of a
     * sentence. This is the one place where a bare dot cannot be trusted.
     */
    private fun isDecimalPoint(text: String, index: Int): Boolean {
        val previous = text.getOrNull(index - 1) ?: return false
        val next = text.getOrNull(index + 1) ?: return false
        return previous.isDigit() && next.isDigit()
    }
}
