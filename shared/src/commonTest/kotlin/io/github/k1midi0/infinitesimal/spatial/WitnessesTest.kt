package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「投递」那一侧的粗筛（§14.37）。
 *
 * 它要钉住的分界是**投递与唤醒是两件事**：同处一片的角色都会收到这一轮发生了什么，而**要唤醒谁**
 * 是判定那一栏说了算。这里只管前半句。
 *
 * 另外两条同样是"错了不报"的：
 *
 * - **发起者自己不在"目击者"里**（它当然知道，但那是 `audience` 最后补上的，不是"框里还有它自己"）。
 * - **没有卡的东西不是角色**（判据是"有没有人设卡"，不是 `kind == "角色"`，§12 第 12 项）——少了
 *   这一条，一只酒杯会被当成"收到了这件事"的对象。
 */
class WitnessesTest {

    private fun character(id: String, x: Int, y: Int) = Entity(
        id = id,
        kind = "角色",
        label = id,
        x = x, y = y, z = 0,
        cards = listOf(CharacterCard("身份", "你是 $id。")),
    )

    /** 玩家：**没有卡**——它照样是人，但不是"角色"那一类（判据见文件头）。 */
    private val player = Entity(id = "player", kind = "角色", label = "你", x = 1200, y = 300, z = 0)

    private val dwarf = character("dwarf", 1000, 200)
    private val bard = character("bard", 3000, 300)
    private val cup = Entity(id = "cup", kind = "器物", label = "酒杯", x = 1200, y = 400, z = 0)

    private fun world() = World.from(WorldSnapshot(listOf(player, dwarf, bard, cup)))

    @Test
    fun onlyTheCharactersInsideTheFrameAreWitnesses() {
        // 矮人离玩家两米出头，在框里；吟游诗人十八米开外，不在；酒杯在框里但没有卡，不是角色。
        assertEquals(listOf("dwarf"), world().witnesses("player"))
    }

    @Test
    fun theActorItselfIsNotAWitness() {
        assertEquals(false, "player" in world().witnesses("player"))
    }

    @Test
    fun theAudienceIsTheFramePlusWhoWasPointedAtPlusWhoWasAffectedPlusTheActor() {
        assertEquals(
            listOf("dwarf", "bard", "cup", "player"),
            world().audience("player", addressed = listOf("bard"), affected = listOf("cup")),
        )
    }

    @Test
    fun theAudienceNeverRepeatsAnybody() {
        // 被点名的人同时也在框里时**只出现一次**——编号列表里重复一个 seq 是幂等的，但排一次让
        // 这一层读起来没有歧义。
        assertEquals(listOf("dwarf", "player"), world().audience("player", addressed = listOf("dwarf")))
    }

    @Test
    fun anActorThatIsNotInTheWorldHasNoAudience() {
        assertEquals(emptyList(), world().witnesses("nobody"))
    }
}
