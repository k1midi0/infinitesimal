package io.github.k1midi0.infinitesimal.progress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **"现在在做什么"那一行字**（§14.58）。
 *
 * 它钉的是三件与它的形状有关的事：**默认没装时什么都不做**、装了之后那句话真的到得了、以及**听的人
 * 自己出问题不许把这一轮弄坏**——最后一条与账本那条纪律是同一个理由（一个诊断把游戏弄坏，比没有诊断
 * 糟得多）。
 */
class ProgressTest {

    /** 装了就要卸：这一格是进程级的，漏一次后面每一条测试都会往那个听的人那里送字。 */
    private fun withSink(sink: (String) -> Unit, block: () -> Unit) {
        Progress.install(sink)
        try {
            block()
        } finally {
            Progress.uninstall()
        }
    }

    @Test
    fun withNobodyListeningNothingHappens() {
        assertNull(Progress.sink, "默认没装——「零侵入」那句的前提")

        Progress.report("正在建模 3 件（并行）…")
    }

    @Test
    fun whatIsReportedReachesTheListener() {
        val heard = mutableListOf<String>()

        withSink({ heard += it }) {
            Progress.report("正在长地形（第 1 轮，4 块）…")
            Progress.report("正在建模：已完成 2/3 件")
        }

        assertEquals(
            listOf("正在长地形（第 1 轮，4 块）…", "正在建模：已完成 2/3 件"),
            heard,
        )
    }

    @Test
    fun aListenerThatThrowsDoesNotBreakTheCaller() {
        withSink({ error("界面那一行字自己坏了") }) {
            Progress.report("正在建模「木箱」（第 3 回来）")
            assertTrue(true, "它抛出来就说明这一条没兜住")
        }
    }

    @Test
    fun afterUninstallingNothingMoreIsHeard() {
        val heard = mutableListOf<String>()
        Progress.install { heard += it }
        Progress.report("第一句")
        Progress.uninstall()
        Progress.report("第二句")

        assertEquals(listOf("第一句"), heard)
    }
}
