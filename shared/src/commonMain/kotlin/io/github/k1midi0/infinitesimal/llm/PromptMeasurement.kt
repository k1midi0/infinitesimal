package io.github.k1midi0.infinitesimal.llm

/**
 * One request as the endpoint accounted for it.
 *
 * `prompt_tokens` counts everything the endpoint read: the messages, the system
 * message, and the tool definitions. It is the only exact number available about how
 * large a request was, and it is exact about the whole thing rather than about the
 * conversation alone — which is why the messages travel with it. Subtracting an
 * estimate of these messages from this count is what separates the part of the
 * request the conversation explains from the part it does not.
 *
 * [messages] is the list that was actually sent, in order, and it is the same list
 * the loop already built to hand to the client. Holding it is holding references,
 * not copies.
 */
data class PromptMeasurement(
    /** `prompt_tokens` from the response. */
    val promptTokens: Int,
    /** The messages that request carried. */
    val messages: List<ChatMessage>,
)
