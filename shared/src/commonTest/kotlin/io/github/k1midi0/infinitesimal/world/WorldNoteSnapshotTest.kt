package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * **笔记跟着世界快照走**（§1.1、§6.3）。
 *
 * 它值得单独一条，因为笔记与对话历史不一样：**历史住在内存里**（`Conversation`，存档还没做），而笔记
 * 挂在 `Entity` 上——也就是说它是**已经能存下来的那一半**。等存档那一刀落地时，它不该是那个还没接上的
 * 字段。
 *
 * 顺带钉住一个容易漏的形状：`Note.supersedes` 指向的是一条**仍在表里**的笔记。所以"装回来"之后
 * [live] 的答案必须与存下去之前一致——不然重启一次，被替代的旧笔记就全都回来了。
 */
class WorldNoteSnapshotTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun notesRideAlongWithTheSnapshotAndKeepTheirReplacementLinks() {
        val world = World.from(
            WorldSnapshot(
                entities = listOf(
                    Entity(
                        id = "dwarf",
                        kind = "角色",
                        label = "杜林",
                        x = 1200,
                        y = 300,
                        z = 0,
                        notes = listOf(
                            Note("n1", "玩家欠我一杯酒。", tag = "约", at = WorldTime.of(1, 18)),
                            Note(
                                id = "n2",
                                text = "那杯酒他已经还了。",
                                at = WorldTime.of(1, 19),
                                supersedes = "n1",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val saved = json.encodeToString(WorldSnapshot.serializer(), world.snapshot())
        val back = World.from(json.decodeFromString(WorldSnapshot.serializer(), saved))

        val notes = back.byId("dwarf")?.notes.orEmpty()
        assertEquals(2, notes.size, "两条都要装回来——被替代的那条没有消失（§6.4）")
        assertEquals("约", notes.first().tag, "标签也要跟着回来")
        assertEquals(WorldTime.of(1, 19), notes.last().at, "写下来的时刻也要跟着回来")
        assertEquals(listOf("n2"), notes.live().map { it.id }, "替代关系要照样算数")
    }
}
