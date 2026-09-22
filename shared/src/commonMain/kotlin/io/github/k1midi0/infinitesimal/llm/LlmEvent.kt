package io.github.k1midi0.infinitesimal.llm

/**
 * What a streamed response produces, already sorted by destination.
 *
 * The distinction between [Reasoning] and [Content] is the one that matters at
 * this layer: reasoning is the model deliberating inside the box, content is
 * material the caller may or may not route to the window. Neither is ever shown
 * to the user directly — the dialogue channel is a tool call.
 */
sealed interface LlmEvent {

    /** The model deliberating. Internal, routes to the logic layer. */
    data class Reasoning(val text: String) : LlmEvent

    /** Free text from the model. In this design it is internal, not speech. */
    data class Content(val text: String) : LlmEvent

    /**
     * A tool call has begun. The endpoint sends the identifier and function name
     * once, in the first fragment, and nothing but arguments afterwards.
     */
    data class ToolCallStarted(
        val index: Int,
        val id: String,
        val name: String,
    ) : LlmEvent

    /**
     * A fragment of a tool call's arguments, to be appended to what came before.
     *
     * Measured against deepseek-flash: fragments can be a single character, so
     * consumers must treat this as a stream of text rather than as something that
     * is individually parseable.
     */
    data class ToolCallArguments(
        val index: Int,
        val fragment: String,
    ) : LlmEvent

    /** The turn ended. [reason] is the endpoint's finish_reason. */
    data class Finished(
        val reason: String?,
        val usage: Usage?,
    ) : LlmEvent
}
