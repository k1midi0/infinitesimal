package io.github.k1midi0.infinitesimal.llm

/**
 * Incremental parser for the Server-Sent Events stream that chat completion
 * endpoints return.
 *
 * Bytes accumulate here until a complete line is available, and only then are
 * they decoded as UTF-8. That ordering is the entire point of this class: a
 * multi-byte character split across two network chunks never reaches the decoder
 * half-formed, because a partial line never reaches the decoder at all.
 *
 * The parser does not care what the payload means. Turning JSON into an assistant
 * message is someone else's job.
 */
class SseParser {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0
    private val dataLines = mutableListOf<String>()
    private var finished = false

    /** One parsed event. */
    sealed interface Event {
        /** A complete event, with its multiple data lines joined by newlines. */
        data class Data(val payload: String) : Event

        /** The server terminated the stream with the conventional sentinel. */
        data object Done : Event
    }

    /**
     * Consumes the next slice of the response body and returns whatever complete
     * events it produced. A slice may yield none, one, or several.
     */
    fun feed(chunk: ByteArray): List<Event> {
        if (finished) return emptyList()

        append(chunk)

        val events = mutableListOf<Event>()
        while (true) {
            val newline = indexOfNewline()
            if (newline < 0) break
            val line = decodeLine(newline)
            dropFirst(newline + 1)
            handleLine(line, events)
            if (finished) break
        }
        return events
    }

    private fun handleLine(line: String, events: MutableList<Event>) {
        // An empty line closes the event being accumulated.
        if (line.isEmpty()) {
            flushPending(events)
            return
        }

        // Lines starting with a colon are comments, used by servers as keep-alives.
        if (line.startsWith(COMMENT_PREFIX)) return

        // event:, id: and retry: carry no meaning for this protocol.
        if (!line.startsWith(DATA_PREFIX)) return

        val payload = line.removePrefix(DATA_PREFIX).removePrefix(" ")
        if (payload == DONE_SENTINEL) {
            flushPending(events)
            finished = true
            events += Event.Done
            return
        }
        dataLines += payload
    }

    private fun flushPending(events: MutableList<Event>) {
        if (dataLines.isEmpty()) return
        events += Event.Data(dataLines.joinToString("\n"))
        dataLines.clear()
    }

    private fun append(chunk: ByteArray) {
        val required = length + chunk.size
        if (required > buffer.size) {
            var capacity = buffer.size
            while (capacity < required) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        chunk.copyInto(buffer, destinationOffset = length)
        length += chunk.size
    }

    private fun indexOfNewline(): Int {
        for (index in 0 until length) {
            if (buffer[index] == NEWLINE) return index
        }
        return -1
    }

    private fun decodeLine(endExclusive: Int): String =
        buffer.decodeToString(startIndex = 0, endIndex = endExclusive).removeSuffix("\r")

    private fun dropFirst(count: Int) {
        buffer.copyInto(
            destination = buffer,
            destinationOffset = 0,
            startIndex = count,
            endIndex = length,
        )
        length -= count
    }

    private companion object {
        const val INITIAL_CAPACITY = 8192
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val COMMENT_PREFIX = ":"
        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"
    }
}
