package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 思考开关与思考强度怎么合成一份请求。
 *
 * 这一条规则只有一处实现（[ThinkingRequest.of]），所以它只需要在这里被钉住一次——两处实现会
 * 漂移，而漂移的方式是其中一处会在思考关着的时候仍然把 effort 发出去。
 */
class ThinkingRequestTest {

    /**
     * 思考关着的时候不带强度。
     *
     * 一场已经被关掉的能力上挂着强度，是这条请求并不表达的意思——它不报错，只是它说的和它做的
     * 不是一回事，而它还会出现在任务日志里。
     */
    @Test
    fun nothingIsAskedForWhenThinkingIsOff() {
        val request = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.MAX)

        assertEquals(ThinkingOptions.Disabled, request.options)
        assertNull(request.effort, "思考关着，强度这一项就不该发")
    }

    @Test
    fun theModeThatThinksCarriesTheChosenEffort() {
        assertEquals(
            "max",
            ThinkingRequest.of(ThinkingMode.Always, ReasoningEffort.MAX).effort,
        )
        assertEquals(
            "low",
            ThinkingRequest.of(ThinkingMode.Always, ReasoningEffort.LOW).effort,
        )
    }

    /**
     * 开关用的是端点认的那两个字符串。它们是从端点对非法取值的拒绝里读出来的，所以不是猜的。
     */
    @Test
    fun theSwitchIsSpelledTheWayTheEndpointSpellsIt() {
        assertEquals("enabled", ThinkingRequest.of(ThinkingMode.Always, ReasoningEffort.HIGH).options.type)
        assertEquals("disabled", ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.HIGH).options.type)
    }

    /**
     * 强度只有三种，尽管端点收得下七种。
     *
     * 它有一张映射表，七个名字落成三种效果。摆七个等于给三档配七个名字，而换一个名字不换效果
     * 正是这个项目一直在禁的那种开关：使用者以为自己调细了，实际什么也没变。
     */
    @Test
    fun onlyTheThreeEffortsThatDifferAreOffered() {
        assertEquals(
            listOf("low", "high", "max"),
            ReasoningEffort.entries.map { it.wireName },
        )
    }

    /**
     * 它落在请求体里的名字。
     *
     * 映射对而字段名发错，症状与拼错一个值一样：端点不会报错，它只会用自己那个默认值——而唯一
     * 的证据是账单与延迟。所以名字本身也要被钉住，而不是只钉住映射。
     */
    @Test
    fun theEffortTravelsUnderTheNameTheEndpointExpects() {
        val json = Json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = "m", messages = emptyList(), reasoningEffort = "max"),
        )

        assertTrue(json.contains("\"reasoning_effort\":\"max\""), json)
    }

    /**
     * 没有强度时那一项根本不出现在请求体里，而不是出现成 null。
     *
     * 「不发这一项」与「发一个空值」对端点是两件事：前者让它用自己的默认，后者是一句它没准备
     * 听的话。
     */
    @Test
    fun anAbsentEffortIsLeftOutOfTheBodyEntirely() {
        val json = Json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = "m", messages = emptyList()),
        )

        assertFalse(json.contains("reasoning_effort"), json)
    }
}
