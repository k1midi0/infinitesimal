package io.github.k1midi0.infinitesimal.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseParserTest {

    private fun SseParser.feedText(text: String) = feed(text.encodeToByteArray())

    @Test
    fun emitsPayloadOfACompleteEvent() {
        val parser = SseParser()

        val events = parser.feedText("data: {\"a\":1}\n\n")

        assertEquals(1, events.size)
        assertEquals(SseParser.Event.Data("{\"a\":1}"), events.single())
    }

    @Test
    fun producesNothingUntilTheEventIsClosed() {
        val parser = SseParser()

        // No blank line yet, so the event is still open.
        assertTrue(parser.feedText("data: {\"a\":1}\n").isEmpty())

        val events = parser.feedText("\n")
        assertEquals(listOf(SseParser.Event.Data("{\"a\":1}")), events)
    }

    @Test
    fun joinsMultipleDataLinesWithNewline() {
        val parser = SseParser()

        val events = parser.feedText("data: first\ndata: second\n\n")

        assertEquals(listOf(SseParser.Event.Data("first\nsecond")), events)
    }

    @Test
    fun acceptsCrlfLineEndings() {
        val parser = SseParser()

        val events = parser.feedText("data: {\"a\":1}\r\n\r\n")

        assertEquals(listOf(SseParser.Event.Data("{\"a\":1}")), events)
    }

    @Test
    fun acceptsPayloadWithoutSpaceAfterColon() {
        val parser = SseParser()

        val events = parser.feedText("data:{\"a\":1}\n\n")

        assertEquals(listOf(SseParser.Event.Data("{\"a\":1}")), events)
    }

    @Test
    fun ignoresCommentsAndUnrelatedFields() {
        val parser = SseParser()

        val events = parser.feedText(": keep-alive\nevent: message\nid: 42\ndata: payload\n\n")

        assertEquals(listOf(SseParser.Event.Data("payload")), events)
    }

    @Test
    fun emitsSeveralEventsFromOneChunk() {
        val parser = SseParser()

        val events = parser.feedText("data: one\n\ndata: two\n\n")

        assertEquals(
            listOf(SseParser.Event.Data("one"), SseParser.Event.Data("two")),
            events,
        )
    }

    @Test
    fun reassemblesALineSplitAcrossChunks() {
        val parser = SseParser()
        val frame = "data: {\"content\":\"hello\"}\n\n".encodeToByteArray()

        val firstHalf = parser.feed(frame.copyOfRange(0, 10))
        assertTrue(firstHalf.isEmpty(), "a partial line must not produce an event")

        val secondHalf = parser.feed(frame.copyOfRange(10, frame.size))
        assertEquals(
            listOf(SseParser.Event.Data("{\"content\":\"hello\"}")),
            secondHalf,
        )
    }

    /**
     * The reason this parser works on bytes rather than strings.
     *
     * A three-byte character is deliberately cut after its first byte. Decoding
     * each network chunk in isolation would turn it into a replacement character
     * and silently corrupt the text, and it would not throw — so the assertion
     * has to check the actual characters, not merely that nothing blew up.
     */
    @Test
    fun decodesMultibyteCharacterSplitAcrossChunks() {
        val parser = SseParser()

        val prefix = "data: {\"content\":\"".encodeToByteArray()
        val character = "你".encodeToByteArray()
        val suffix = "\"}\n\n".encodeToByteArray()
        assertEquals(3, character.size, "the test assumes a three-byte character")

        val frame = prefix + character + suffix
        val cut = prefix.size + 1

        assertTrue(parser.feed(frame.copyOfRange(0, cut)).isEmpty())

        val events = parser.feed(frame.copyOfRange(cut, frame.size))

        assertEquals(1, events.size)
        val payload = (events.single() as SseParser.Event.Data).payload
        assertEquals("{\"content\":\"你\"}", payload)
        assertTrue(payload.contains('你'), "the character must survive intact")
    }

    @Test
    fun stopsAtTheDoneSentinel() {
        val parser = SseParser()

        val events = parser.feedText("data: {\"a\":1}\n\ndata: [DONE]\n\n")

        assertEquals(
            listOf(SseParser.Event.Data("{\"a\":1}"), SseParser.Event.Done),
            events,
        )
    }

    @Test
    fun ignoresEverythingAfterDone() {
        val parser = SseParser()

        parser.feedText("data: [DONE]\n\n")
        val after = parser.feedText("data: {\"late\":true}\n\n")

        assertTrue(after.isEmpty())
    }
}
