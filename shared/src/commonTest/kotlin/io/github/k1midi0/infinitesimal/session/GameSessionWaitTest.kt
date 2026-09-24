package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「等到下一件事」从界面到时间轴的那一段接线（§8.1）。
 *
 * [FastForwardTest] 已经把算法本身钉住了，这里钉的是另外两件只有接线才会错的事：**开局那一刻是不是
 * 内容里写的那个时辰**（对不上，模型第一句话就露馅），以及**等待之后界面看得到的时间有没有跟着走**
 * （`GameSession.clock` 是一个推给界面的状态，不是界面去问世界——忘了推，那行字会永远停在开局）。
 */
class GameSessionWaitTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    /** 等一次不叫模型，所以这里不需要假端点；真端点也不会被碰到。 */
    private fun session() = GameSession(config)

    private fun entry(id: String, at: WorldTime, what: String) =
        TimelineEntry(id = id, dueAt = at, what = what)

    @Test
    fun aFreshGameStartsAtTheEveningTheTavernWasWrittenIn() {
        val session = session()
        assertEquals(WorldTime.of(1, 18), session.clock)
        assertEquals("第 1 天 傍晚（18:00）", session.clock.toString())
    }

    /** 开局世界的时间轴是空的——那意味着**没人安排过接下来会发生什么**，界面据此把按钮禁掉。 */
    @Test
    fun withNothingOnTheTimelineThereIsNoStopAndTheClockDoesNotMove() = runBlocking {
        val session = session()
        assertEquals(null, session.nextStop)

        session.waitUntilNextNow()

        assertEquals(WorldTime.of(1, 18), session.clock, "没有可等的就不能凭空往前推")
        assertTrue(session.log.any { it.text.contains("等不到什么") }, "得说一句，不能让玩家以为卡住了")
    }

    @Test
    fun waitingLandsOnTheFirstThingAndSaysWhatHappened() = runBlocking {
        val session = session()
        session.world.timeline.put(entry("fire", WorldTime.of(2, 2), "邻居家着了火"))
        session.world.timeline.put(entry("dawn", WorldTime.of(2, 6), "天亮了"))

        assertEquals(WorldTime.of(2, 2), session.nextStop, "界面按钮上写的就是这个时刻")
        session.waitUntilNextNow()

        assertEquals(WorldTime.of(2, 2), session.clock, "状态要跟着世界一起走")
        assertTrue(session.log.any { it.text.contains("邻居家着了火") }, "发生了什么必须说出来")
        assertTrue(session.log.none { it.text.contains("天亮了") }, "第二件事还没到点，不能说")
        assertEquals(WorldTime.of(2, 6), session.nextStop, "它还在轴上等着")
    }

    /** 到期的取走之后就没了。**再等一次才轮到第二件**——一次等待只停一次。 */
    @Test
    fun waitingAgainWalksOnToTheNextThing() = runBlocking {
        val session = session()
        session.world.timeline.put(entry("fire", WorldTime.of(2, 2), "邻居家着了火"))
        session.world.timeline.put(entry("dawn", WorldTime.of(2, 6), "天亮了"))

        session.waitUntilNextNow()
        session.waitUntilNextNow()

        assertEquals(WorldTime.of(2, 6), session.clock)
        assertTrue(session.log.any { it.text.contains("天亮了") })
        assertEquals(null, session.nextStop, "两件都过了，就没什么可等的了")
    }
}
