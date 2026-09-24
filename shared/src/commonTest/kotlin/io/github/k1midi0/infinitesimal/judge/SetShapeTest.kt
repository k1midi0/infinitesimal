package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.ChangeLevel
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.Voxel
import io.github.k1midi0.infinitesimal.world.VoxelShape
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 建模造出来的形状怎么**落库**（[Consequence.SetShape]）。
 *
 * 这里钉的是一条不显然的顺序规则：`SetShape` 常常作用在**同一批里刚 spawn 出来的东西**上，而
 * [Committer] 是按顺序走影子状态的——形状排在它的东西前面，就会撞上"世界里没有它"。把它写成测试，
 * 是因为它坏掉的方式很安静：真跑起来模型先调 spawn、再调 model_shape，顺序恰好是对的；只有哪天有人
 * 去动两份后果合并的次序才会炸，而那时症状是"新东西没有形状"，看起来像建模失败了。
 */
class SetShapeTest {

    private val shape = VoxelShape(solid = listOf(Voxel(0, 0, 0), Voxel(5, 0, 0)))

    private fun half() = Entity(id = "table_half", kind = "家具", label = "半张木桌", x = 0, y = 0, z = 0)

    @Test
    fun aShapeLandsOnAThingSpawnedInTheSameBatch() {
        val world = World.empty()

        val result = Committer.apply(
            world,
            listOf(Consequence.Spawn(half()), Consequence.SetShape("table_half", shape)),
        )

        assertIs<Committer.Applied>(result)
        assertEquals(shape, world.byId("table_half")?.shape)
    }

    @Test
    fun aShapeBeforeTheThingItBelongsToIsRejectedAsAWhole() {
        val world = World.empty()

        val result = Committer.apply(
            world,
            listOf(Consequence.SetShape("table_half", shape), Consequence.Spawn(half())),
        )

        val rejected = assertIs<Committer.Rejected>(result)
        assertTrue(rejected.problems.any { it.contains("世界里没有 table_half") }, "${rejected.problems}")
        // **一条不过，整批不落库**（§4.2）——半张桌子也不该进去。
        assertNull(world.byId("table_half"))
    }

    @Test
    fun aShapeOnSomethingDestroyedInTheSameBatchIsRejected() {
        val world = World.empty()
        world.put(half())

        val result = Committer.apply(
            world,
            listOf(Consequence.Destroy("table_half"), Consequence.SetShape("table_half", shape)),
        )

        val rejected = assertIs<Committer.Rejected>(result)
        assertTrue(rejected.problems.any { it.contains("已经在这一批里被收走了") }, "${rejected.problems}")
        // 整批不落库：连销毁也没发生，那半张桌子还在。
        assertTrue(world.byId("table_half") != null)
    }

    @Test
    fun aShapeOnSomethingThatAlreadyExistsReplacesOnlyItsOwnCopy() {
        val world = World.empty()
        val original = VoxelShape(solid = listOf(Voxel(0, 0, 0)))
        world.put(half().copy(shape = original))

        val result = Committer.apply(world, listOf(Consequence.SetShape("table_half", shape)))

        assertIs<Committer.Applied>(result)
        assertEquals(shape, world.byId("table_half")?.shape)
        // 原先那一份一个格都没被动过——形状不可变，这就是 A.9"工厂不是引用"落地的方式。
        assertEquals(1, original.cellCount)
    }

    @Test
    fun settingAShapeInvalidatesTheOlderDescription() {
        val world = World.empty()
        world.put(half().copy(sentences = listOf("半张木桌斜靠在墙边。")))

        Committer.apply(world, listOf(Consequence.SetShape("table_half", shape)))

        // 几何变了，既有描述就不再是它的样子（§7.1）：算外表变化，描述作废。
        assertEquals(ChangeLevel.APPEARANCE, Consequence.SetShape("table_half", shape).level)
        assertTrue(world.byId("table_half")!!.sentences.isEmpty(), "描述该被作废")
    }
}
