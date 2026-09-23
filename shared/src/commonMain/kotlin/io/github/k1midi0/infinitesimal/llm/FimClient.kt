package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FimRequest(
    val model: String,
    val prompt: String,
    val suffix: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class FimChoice(
    val text: String = "",
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class FimResponse(
    val choices: List<FimChoice> = emptyList(),
    val usage: Usage? = null,
)

data class FimResult(
    val text: String,
    val finishReason: String?,
    val usage: Usage?,
)

/**
 * FIM（Fill In the Middle）补全。Beta。
 *
 * **它不是 `LlmClient` 的另一个方法，因为它是另一条协议。** 端点不同（`/beta/completions`）、
 * 请求形状不同（`prompt` + `suffix`，不是 `messages`）、响应形状也不同（`choices[0].text`，不是
 * message，没有工具、没有思考）。把它塞进 `LlmClient` 会让那个类里出现两套互不相干的字段规则。
 *
 * 它唯一的价值在这一句上：**前缀与后缀由端点原样保留**。也就是说"只增不改"不是靠提示词求来的，
 * 是协议保证的——模型根本没有机会碰到两侧的文字。
 *
 * 代价是能力上的两处缺口，都在文档里：
 *  - **最大补全 4K**（对一句话远远够用，但它不能用来生成整段描述）
 *  - 会随 Beta 变动。**所以工具那条路必须一直在**：没有 FIM 能力的模型上，插入只能靠工具。
 *
 * 超时沿用 [LlmClient.createHttpClient]：那一套是为流式调的（总超时关掉、socket 超时留着），
 * 用在一次性请求上同样成立——一次补全也可能因为排队而慢，但连接静默死掉要能被抓住。
 */
class FimClient(
    private val configuration: () -> EndpointConfig,
    private val httpClient: HttpClient = LlmClient.createHttpClient(),
) {
    constructor(config: EndpointConfig, httpClient: HttpClient = LlmClient.createHttpClient()) :
        this({ config }, httpClient)

    suspend fun complete(prompt: String, suffix: String? = null, maxTokens: Int = 256): FimResult {
        val config = configuration()
        val missing = config.missingFields()
        if (missing.isNotEmpty()) throw LlmError.NotConfigured(missing)

        val request = FimRequest(
            model = config.model,
            prompt = prompt,
            suffix = suffix?.takeIf { it.isNotEmpty() },
            maxTokens = maxTokens,
        )

        val response = try {
            httpClient.preparePost(config.fimCompletionsUrl) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(FimRequest.serializer(), request))
            }.execute()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            throw LlmError.Network(cause)
        }

        if (!response.status.isSuccess()) {
            // 状态码比消息体更有用，所以读不出体也不能把它丢掉——与 LlmClient 同一个处置。
            val body = try {
                response.bodyAsText()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                null
            }
            throw LlmError.fromStatus(response.status.value, extractApiMessage(body))
        }

        val body = response.bodyAsText()
        val parsed = try {
            JSON.decodeFromString(FimResponse.serializer(), body)
        } catch (cause: Throwable) {
            throw LlmError.Parse(body.take(500), cause)
        }

        // 空的 choices 是一种真实的失败（例如服务端换了形状），不静默当成空补全。
        val choice = parsed.choices.firstOrNull()
            ?: throw LlmError.Parse("FIM 响应里没有 choices：${body.take(200)}", null)

        return FimResult(
            text = choice.text,
            finishReason = choice.finishReason,
            usage = parsed.usage,
        )
    }

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
