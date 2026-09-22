package io.github.k1midi0.infinitesimal.llm

/**
 * Every way a model request can fail, as distinct types.
 *
 * Callers branch on these to tell the user something useful. Collapsing them into
 * one "request failed" would make a wrong API key indistinguishable from a
 * dropped connection, and the two need opposite responses from the user.
 */
sealed class LlmError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Settings are incomplete. Nothing was sent. */
    class NotConfigured(val missingFields: List<String>) : LlmError(
        "缺少配置项：${missingFields.joinToString("、")}",
    )

    /** The request never completed. */
    class Network(cause: Throwable) : LlmError(
        "网络不可达：${cause.message ?: cause.toString()}",
        cause,
    )

    /** The endpoint rejected the credential. */
    class Unauthorized(detail: String?) : LlmError(
        "认证失败，请检查 api key${detail.asDetail()}",
    )

    /** Quota exhausted or too many requests. */
    class RateLimited(detail: String?) : LlmError(
        "限流或额度不足${detail.asDetail()}",
    )

    /** The request itself was unacceptable, usually a wrong model name. */
    class BadRequest(detail: String?) : LlmError(
        "请求被拒绝，请检查 model 名与参数${detail.asDetail()}",
    )

    /** The endpoint failed. Retrying may help. */
    class ServerError(val status: Int, detail: String?) : LlmError(
        "服务端错误 $status${detail.asDetail()}",
    )

    /** A status outside the documented set. Surfaced rather than guessed at. */
    class UnexpectedStatus(val status: Int, detail: String?) : LlmError(
        "未预期的状态码 $status${detail.asDetail()}",
    )

    /** The response body did not match the documented shape. */
    class Parse(val detail: String, cause: Throwable?) : LlmError(
        "响应解析失败：$detail",
        cause,
    )

    companion object {
        fun fromStatus(status: Int, body: String?): LlmError = when (status) {
            401, 403 -> Unauthorized(body)
            429 -> RateLimited(body)
            400, 404, 422 -> BadRequest(body)
            in 500..599 -> ServerError(status, body)
            else -> UnexpectedStatus(status, body)
        }
    }
}

private fun String?.asDetail(): String =
    if (this.isNullOrBlank()) "" else "：$this"
