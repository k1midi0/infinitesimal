package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
