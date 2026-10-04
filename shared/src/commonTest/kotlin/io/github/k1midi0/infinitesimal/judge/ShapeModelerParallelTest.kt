package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **一次画一批**（§14.58）：用户定的原话是「建模应该是可以并行的，可以一次性给出要的物品名单，并行
 * 建模」。
 *
 * ## 这一支为什么要一个"到齐才回话"的假端点
 *
 * 因为**"并行了"这件事本身没有别的观测方式**：一件一件画也能画完、也能把每件都放进库，从结果上看不出
 * 差别——差别只在"三件是不是同时在飞"。所以这一支的假端点先等三件都进来，再一起回话：
 *
 *  - 真的并行 → 三件同时在飞（[Together.mostAtOnce] == 3），测试几毫秒就过；
 *  - 还是串行 → 第一件就等不到另外两件（它根本还没被发出去），五秒之后超时回话，而
 *    `mostAtOnce` 永远是 1——**断言会说出这件事**。
 */
class ShapeModelerParallelTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    /** 一句话要求 → 这一件那一轮的回话（一层 + 收工，所以一件只要一次请求）。 */
    private fun piece(name: String, rows: String = "##\n##") = multiTurn(
        Triple("f0", "voxel_floor", """{"z":0,"rows":"$rows"}"""),
        Triple("done", "voxel_done", """{"name":"$name"}"""),
    )

    /**
     * 假端点：**等 [answers] 里所有的要求都到齐了再回话**，并按请求体里出现的是哪一句要求挑回话。
     *
     * 它自己数着"同时有几件在飞"（[mostAtOnce]）——那是这一支唯一的判据。
     */
    private class Together(private val answers: Map<String, String>) {
        val bodies = mutableListOf<String>()
        var mostAtOnce = 0
            private set

        private var inFlight = 0
        private val lock = Mutex()
        private val all = CompletableDeferred<Unit>()

        val client: HttpClient = HttpClient(
            MockEngine { request ->
                val body = (request.body as? TextContent)?.text.orEmpty()
                val shouldWait = lock.withLock {
                    bodies += body
                    inFlight++
                    mostAtOnce = maxOf(mostAtOnce, inFlight)
                    if (inFlight >= answers.size) all.complete(Unit)
                    inFlight < answers.size
                }
                if (shouldWait) withTimeoutOrNull(5_000) { all.await() }
                lock.withLock { inFlight-- }
                respond(
                    content = answers.getValue(answers.keys.first { body.contains(it) }),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ) {
            expectSuccess = false
        }
    }

    @Test
    fun aBatchIsDrawnAllAtOnceInsteadOfOneAfterAnother() = runBlocking {
        val fake = Together(
            mapOf(
                "木头凳子" to piece("stool", "###\n###"),
                "铁钉" to piece("nail", "#"),
                "麻绳" to piece("rope", "#"),
            ),
        )
        val modeler = ShapeModeler(LlmClient(config, fake.client))

        val drawn = modeler.modelAll(listOf("木头凳子", "铁钉", "麻绳"))

        assertEquals(3, drawn.size)
        assertTrue(
            drawn.values.all { it is ShapeModeler.Result.Modeled },
            "三件都该画成：${drawn.mapValues { it.value }}",
        )
        assertEquals(
            3,
            fake.mostAtOnce,
            "三件必须同时在飞——一件一件画的话这里永远是 1（而那正是这一条要禁掉的东西）",
        )
        assertEquals(
            listOf("stool", "nail", "rope"),
            modeler.presets.generatedPresets().map { it.name },
            "落库按名单的顺序——并发写一个 LinkedHashMap 会让它坏掉，所以画完之后的落库是串行的",
        )
    }

    @Test
    fun whatIsAlreadyInTheLibraryIsNotDrawnAgain() = runBlocking {
        val fake = Together(mapOf("木头凳子" to piece("stool"), "铁钉" to piece("nail")))
        val modeler = ShapeModeler(LlmClient(config, fake.client))

        modeler.modelAll(listOf("木头凳子", "铁钉"))
        // 同一句要求再来一次（外加一句重复的）：**一次请求都不该多花**。
        val again = modeler.modelAll(listOf("木头凳子", "铁钉", "木头凳子"))

        assertEquals(2, fake.bodies.size, "命中库的那些不该重画")
        assertEquals(2, again.size, "同一句要求只算一条")
        assertTrue(again.values.all { assertIs<ShapeModeler.Result.Modeled>(it).cached })
    }

    @Test
    fun whatCouldNotBeDrawnIsNotStored() = runBlocking {
        val script = Scripted(listOf(turn("g", "give_up", """{"reason":"这一句画不出来"}""")))
        val modeler = ShapeModeler(LlmClient(config, script.client))

        val drawn = modeler.modelAll(listOf("画不出来的东西"))

        val failed = assertIs<ShapeModeler.Result.Failed>(drawn.getValue("画不出来的东西"))
        assertTrue(failed.problems.any { it.contains("画不出来") }, "理由要原样留着：${failed.problems}")
        assertTrue(
            modeler.presets.generatedPresets().isEmpty(),
            "没画成的不该进库——进了的话下一次它会当成「画过」直接拿出来",
        )
    }

    /** 名单空着时一次调用都不花（生成那一轮常常一件要现造的都没有）。 */
    @Test
    fun anEmptyBatchCostsNothing() = runBlocking {
        val fake = Together(mapOf("木头凳子" to piece("stool")))
        val modeler = ShapeModeler(LlmClient(config, fake.client))

        assertTrue(modeler.modelAll(listOf("", "   ")).isEmpty())
        assertEquals(0, fake.bodies.size)
    }
}
