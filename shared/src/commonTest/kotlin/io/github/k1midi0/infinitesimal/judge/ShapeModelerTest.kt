package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 建模那一段的回合，用假端点钉住（附录 A.9–A.11）。
 *
 * 能离线钉的正是最要紧的那几件：**交上来的层有没有被拼成一张图**、**画错时问题有没有原样回去**、
 * **画不出来时有没有一条能诊断的失败**。让真模型来触发这些，等于把"重试有没有把问题说清楚"交给运气
 * ——而重试的全部价值就在那句话里。
 */
class ShapeModelerTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private fun modeler(script: Scripted) = ShapeModeler(LlmClient(config, script.client))

    @Test
    fun floorsAreAssembledIntoOnePictureAndValidated() = runBlocking {
        val script = Scripted(
            listOf(
                turn("f0", "voxel_floor", """{"z":0,"rows":"##\n##"}"""),
                turn("f5", "voxel_floor", """{"z":5,"rows":"..\n#."}"""),
                turn("done", "voxel_done", """{"name":"Slab"}"""),
            ),
        )

        val result = modeler(script).model("一块两格见方的木板")

        val modeled = assertIs<ShapeModeler.Result.Modeled>(result)
        // 名字被收拾成能用的样子——它只是个把手，不值得为它多烧一轮。
        assertEquals("slab", modeled.name)
        // 两层：z=0 有 4 格，z=5 有 1 格。
        assertEquals(5, modeled.shape.cellCount)
        assertEquals(listOf(0, 5), modeled.shape.solid.map { it.z }.distinct().sorted())
    }

    @Test
    fun aBadPictureComesBackWithTheProblemsAndTheModelGetsAnotherGo() = runBlocking {
        val script = Scripted(
            listOf(
                // 第一行三个字符、第二行两个：这正是"少画了几个字符"那种画错。
                turn("f0", "voxel_floor", """{"z":0,"rows":"###\n##"}"""),
                turn("done", "voxel_done", """{"name":"slab"}"""),
                turn("f0b", "voxel_floor", """{"z":0,"rows":"##\n##"}"""),
                turn("done2", "voxel_done", """{"name":"slab"}"""),
            ),
        )

        val result = modeler(script).model("一块两格见方的木板")

        val modeled = assertIs<ShapeModeler.Result.Modeled>(result)
        assertEquals(4, modeled.shape.cellCount)
        assertEquals(4, script.bodies.size, "错一次、改一次，正好四个来回")

        // **问题必须真的回到模型手里**，否则重试只是原样再问一遍。
        val third = script.bodies[2]
        assertTrue(third.contains("一样长"), "重试的请求里没有那条问题：$third")
        // 而且它还得看见自己交过的那一层——不然它不知道改哪一层。
        assertTrue(third.contains("第 0 层记下了") || third.contains("voxel_floor"), third)
    }

    @Test
    fun givingUpIsAFailureWithTheReasonKept() = runBlocking {
        val script = Scripted(
            listOf(turn("give", "give_up", """{"reason":"不知道断面该多粗糙"}""")),
        )

        val result = modeler(script).model("被劈开的橡木桌的一半")

        val failed = assertIs<ShapeModeler.Result.Failed>(result)
        assertEquals(1, failed.problems.size)
        assertTrue(failed.problems.first().contains("断面该多粗糙"), failed.problems.first())
    }

    @Test
    fun sayingNothingIsNotAFailureOfTheEndpoint() = runBlocking {
        // 一轮里一个工具都没调：这是**可以指出来的**，不该当成设备故障，也不该当成"它交了一张图"。
        val script = Scripted(
            listOf(
                sse("""{"choices":[{"delta":{"content":"这是一张桌子："},"finish_reason":"stop"}]}"""),
                turn("f0", "voxel_floor", """{"z":0,"rows":"#"}"""),
                turn("done", "voxel_done", """{"name":"peg"}"""),
            ),
        )

        val result = modeler(script).model("一根木钉")

        assertIs<ShapeModeler.Result.Modeled>(result)
        val second = script.bodies[1]
        assertTrue(second.contains("你没有调用任何工具"), "重试的请求里没有那条说明：$second")
    }

    /**
     * 一句话都没说、一个工具都没调的那一轮，**不能留在历史里**。
     *
     * 上面那条测的是「要能指出它、而且不烧掉一次尝试」；这一条测的是**那条消息本身不许上线**。
     * 端点两次拒的都是它：先是因为 `tool_calls` 是个空数组，改成「字段不出现」之后，报错立刻换成
     * `Invalid assistant message: content or tool_calls must be set`——两样都没有。
     *
     * 这不是洁癖：真发生的路径是**生成器调 `make_object` → 建模那一层恰好走一次空回合**，
     * 500 多个地形格铺完之后，坏在一个用户看不到、日志里也只有一个 400 的地方。
     */
    @Test
    fun anEmptyTurnNeverEntersTheHistory() = runBlocking {
        val script = Scripted(
            listOf(
                stop(),
                turn("f0", "voxel_floor", """{"z":0,"rows":"#"}"""),
                turn("done", "voxel_done", """{"name":"peg"}"""),
            ),
        )

        assertIs<ShapeModeler.Result.Modeled>(modeler(script).model("一根木钉"))

        val second = script.bodies[1]
        assertTrue(second.contains("你没有调用任何工具"), "那条说明还是得回去：$second")
        assertFalse(second.contains("\"role\":\"assistant\""), "空的那一轮不该留下助手消息：$second")
    }

    @Test
    fun aWrongZComesBackWithoutBurningAWholeAttempt() = runBlocking {
        val script = Scripted(
            listOf(
                turn("f0", "voxel_floor", """{"z":7,"rows":"#"}"""),
                turn("f0b", "voxel_floor", """{"z":0,"rows":"#"}"""),
                turn("done", "voxel_done", """{"name":"peg"}"""),
            ),
        )

        val modeled = assertIs<ShapeModeler.Result.Modeled>(modeler(script).model("一根木钉"))
        assertEquals(1, modeled.shape.cellCount)
        assertEquals(0, modeled.shape.solid.single().z)
    }

    @Test
    fun theSameRequirementIsNeverDrawnTwice() = runBlocking {
        // 用户定的：**缓存键是那句要求。** 所以只有两帧的脚本能挡住两次调用——第二次根本不该发请求。
        val script = Scripted(
            listOf(
                turn("f0", "voxel_floor", """{"z":0,"rows":"##\n##"}"""),
                turn("done", "voxel_done", """{"name":"board"}"""),
            ),
        )
        val modeler = modeler(script)
        val brief = "一块两格见方的木板"

        val first = assertIs<ShapeModeler.Result.Modeled>(modeler.model(brief))
        assertFalse(first.cached, "第一次不该是命中")
        val requestsAfterFirst = modeler.requestCount

        val second = assertIs<ShapeModeler.Result.Modeled>(modeler.model(brief))

        assertTrue(second.cached, "同一句要求第二次该直接拿回来")
        assertEquals(first.shape, second.shape)
        assertEquals(requestsAfterFirst, modeler.requestCount, "命中不该发请求")

        // 而它不只是一份加速表：**产物是一条预设**，进了生成包（用户定的，附录 A.14）。
        assertEquals(1, modeler.presets.generatedPresets().size)
        assertEquals(brief, modeler.presets.generatedPresets().single().brief)
        assertEquals(first.shape, modeler.presets.byBrief(brief)?.shape)
    }

    @Test
    fun aDifferentRequirementIsNotACacheHit() = runBlocking {
        val script = Scripted(
            listOf(
                turn("f0", "voxel_floor", """{"z":0,"rows":"##\n##"}"""),
                turn("done", "voxel_done", """{"name":"board"}"""),
                turn("f0b", "voxel_floor", """{"z":0,"rows":"#"}"""),
                turn("done2", "voxel_done", """{"name":"peg"}"""),
            ),
        )
        val modeler = modeler(script)

        assertIs<ShapeModeler.Result.Modeled>(modeler.model("一块两格见方的木板"))
        val second = assertIs<ShapeModeler.Result.Modeled>(modeler.model("一根木钉"))

        assertFalse(second.cached)
        assertEquals(4, modeler.requestCount)
        assertEquals(2, modeler.presets.generatedPresets().size)
    }

    @Test
    fun theGridLanguageIsSpelledOutInTheSystemPrompt() = runBlocking {
        val script = Scripted(listOf(turn("give", "give_up", """{"reason":"test"}""")))
        modeler(script).model("随便什么")

        val first = script.bodies[0]
        // 模型要学的只有这一种表示法，而它得在**第一次**请求里就看到它。
        assertTrue(first.contains("voxel_floor"), first)
        assertTrue(first.contains("voxel_done"), first)
        assertTrue(first.contains("z=0") || first.contains("离地"), first)
        // 单边上限与格数上限也要写出来，否则它无从知道"多大算太大"。
        assertTrue(first.contains("200"), first)
    }

    // ── 属性总结（A.14、§14.31）─────────────────────────────────────────────────────
    //
    // 形状与"它是什么"一起交、一起存。分开两处存的话，同一份形状会有两个来源，而它们迟早对不上。

    @Test
    fun theShapeAndItsAttributeSummaryAreStoredTogether() = runBlocking {
        val script = Scripted(
            listOf(
                turn("f0", "voxel_floor", """{"z":0,"rows":"##\n##"}"""),
                turn(
                    "done",
                    "voxel_done",
                    """{"name":"oak","category":"树木","tags":"橡树、乔木、落叶"}""",
                ),
            ),
        )
        val modeler = modeler(script)
        val brief = "一棵高大的橡树"

        val modeled = assertIs<ShapeModeler.Result.Modeled>(modeler.model(brief))
        assertEquals("树木", modeled.category)
        assertEquals(listOf("橡树", "乔木", "落叶"), modeled.tags)

        val stored = modeler.presets.byBrief(brief)
        assertEquals("树木", stored?.category)
        assertEquals(listOf("橡树", "乔木", "落叶"), stored?.tags)

        // 命中缓存那条路上，属性来自库里那条老预设——**不是重新问一遍模型**：同一份形状本来就该有
        // 同一份属性，再问一次只会让两次调用给出两个略略不同的类别。
        val again = assertIs<ShapeModeler.Result.Modeled>(modeler.model(brief))
        assertTrue(again.cached)
        assertEquals("树木", again.category)
        assertEquals(listOf("橡树", "乔木", "落叶"), again.tags)
    }

    @Test
    fun tagsAreSplitOnWhateverSeparatorTheModelHappenedToUse() = runBlocking {
        // 切不开的代价不对称：一条「木钉, 木制、小件」整着存进去，搜「木制」就永远搜不到它。
        val script = Scripted(
            listOf(
                turn("f0", "voxel_floor", """{"z":0,"rows":"#"}"""),
                turn(
                    "done",
                    "voxel_done",
                    """{"name":"peg","category":"器具","tags":"木钉, 木制、小件|可钉 木钉"}""",
                ),
            ),
        )

        val modeled = assertIs<ShapeModeler.Result.Modeled>(modeler(script).model("一根木钉"))

        assertEquals(listOf("木钉", "木制", "小件", "可钉"), modeled.tags, "去空格、去重、保序")
    }

    @Test
    fun aMissingAttributeSummaryIsNotAFailureOfTheDrawing() = runBlocking {
        // 属性是给搜索用的，形状才是这一次要的东西。老数据、或者模型漏填，都不该让一次画得对的建模作废。
        val script = Scripted(
            listOf(
                turn("f0", "voxel_floor", """{"z":0,"rows":"#"}"""),
                turn("done", "voxel_done", """{"name":"peg"}"""),
            ),
        )

        val modeled = assertIs<ShapeModeler.Result.Modeled>(modeler(script).model("一根木钉"))

        assertEquals("", modeled.category)
        assertTrue(modeled.tags.isEmpty())
    }
}
