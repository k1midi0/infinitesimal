package io.github.k1midi0.infinitesimal.spike

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 把 `usage` 的**原文**打出来。
 *
 * 缓存探针七次全 0，但那七次读的是我们自己解析出来的 `promptCacheHitTokens`——它在模型里
 * **带默认值 0**。所以"命中 0"有两种可能，而它们的结论完全相反：
 *
 *  - 端点**返回了** `"prompt_cache_hit_tokens": 0` → 缓存确实没生效
 *  - 端点**没返回**这两个字段 → 我们根本没有读数，那个 0 是默认值，什么都没证明
 *
 * 不看原文就分不开这两件事。所以这里绕开 `LlmClient`，直接发一个非流式请求，把响应尾部原样打出。
 */
class RawUsageProbeSpike {

    private val keyFile: File?
        get() = System.getProperty("infinitesimal.liveKeyFile")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    @Test
    fun dumpRawUsage() {
        val file = keyFile
        if (file == null || !file.isFile) {
            println("[raw usage probe 未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        val key = file.readText().trim()

        val body = """
            {"model":"deepseek-flash",
             "messages":[{"role":"system","content":"你是一个观察器。用中文回答。"},
                         {"role":"user","content":"对象：木桌。材质橡木。请用一句话描述它。"}],
             "stream":false,
             "max_tokens":48,
             "user_id":"inf-renderer"}
        """.trimIndent()

        runBlocking {
            println()
            println("═".repeat(96))
            println("原始 usage：绕开客户端，直接看端点返回了什么")
            println("═".repeat(96))
            HttpClient { expectSuccess = false }.use { client ->
                send(client, key, body, "① 首次")
                println("     …等 6 秒")
                delay(6_000)
                send(client, key, body, "② 完全相同的请求再来一次")
            }
            println("═".repeat(96))
        }
    }

    private suspend fun send(client: HttpClient, key: String, body: String, label: String) {
        val response = client.post("https://api.deepseek.com/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        println()
        println("▸ $label（HTTP ${response.status.value}，响应 ${text.length} 字符）")
        // usage 在响应尾部；把尾部原样打出来，比用正则去猜嵌套结构可靠。
        println("  尾部 420 字符：")
        println("  " + text.takeLast(420).replace("\n", "\n  "))
    }
}
