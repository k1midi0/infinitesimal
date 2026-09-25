package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 待办（§3.2.5、§14.37）。
 *
 * 这里只有一条规则，但它错起来很像对的：**到点不等于自动发生，到点只是"该提醒它了"**。而过期的也
 * 要提醒——一个三天没被唤醒的角色，醒来时该知道自己三天前打算做什么，而不是一片空白。
 */
class TodoTest {

    private fun at(day: Int, hour: Int, minute: Int = 0) = WorldTime.of(day, hour, minute)

    @Test
    fun onlyTheOnesThatHaveComeDueAreSurfaced() {
        val todos = listOf(
            Todo(at(1, 17, 30), "塞窗缝"),
            Todo(at(3, 9), "去东街找木匠"),
            Todo(at(1, 12), "还酒钱"),
        )

        assertEquals(
            listOf("还酒钱", "塞窗缝"),
            todos.due(at(1, 18)).map { it.what },
        )
    }

    @Test
    fun somethingThatCameDueLongAgoStillGetsSaid() {
        // 一个三天没被唤醒的角色，醒来时该知道自己三天前打算做什么。
        val todos = listOf(Todo(at(1, 9), "把地窖的门修好"))
        assertEquals(1, todos.due(at(4, 20)).size)
    }

    @Test
    fun theExactMinuteCountsAsDue() {
        val todos = listOf(Todo(at(1, 18), "开门"))
        assertEquals(1, todos.due(at(1, 18)).size)
    }

    @Test
    fun nothingDueMeansNothingIsSaid() {
        assertEquals(emptyList(), listOf(Todo(at(5, 9), "远着呢")).due(at(1, 18)))
    }
}
