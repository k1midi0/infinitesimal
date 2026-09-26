package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.schedule.SentryJudge
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * **联机探针：哨兵认不认得出"这一格现在做不了"**（§14.45）。
 *
 * 这一刀的两半里，地形那一半（站得住吗）由单测钉得死死的，而**语义那一半只有联机能验**：代码判不出
 * "酒馆成了火海"，那是要一个模型读一眼的。所以这一支就只问一件事——
 *
 * > **把一张桌子点着、摆在它该去喝酒的那个位置上，它会不会说"这一格做不了"，而理由能不能交给导演。**
 *
 * 三个对照：
 *
 *  1. **没事的那一格**（在柜台后面擦杯子，周围一切正常）→ 它该说"做得了"。这一条是**成本的那一半**：
 *     一个动不动就说"做不了"的哨兵会把导演每一轮都叫起来。
 *  2. **烧起来的那一格**（那一格上摆着一堆烧焦的东西、浓烟、塌下来的屋梁）→ 该说"做不了"，
 *     而且理由里要说得出"火 / 烧 / 塌"。
 *  3. **看不出什么不对但人不在那儿**（地点离得很远、那一片看不见）→ 该说"做得了"（规矩：
 *     看不到就报做得了，不能拿"我没看见"当"那里出事了"）。
 *
 * 次数小（三次）。它要的不是统计，而是"这条路真的通"——模型在这件事上会不会答非所问。
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*SentryProbe*" --rerun `
 *   -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 * ```
 */
class SentryProbe {

    private fun world(burned: Boolean, farAway: Boolean = false): World {
        val base = if (farAway) 900_000 else 1200
        val hall = Entity(
            id = "tavern_hall", kind = "地点", label = "酒馆大厅",
            x = base, y = 400, z = ProbeSupport.GROUND,
            attributes = mapOf("地面" to "夯土", "光照" to "几盏油灯"),
            background = if (burned) {
                "「醉猫」酒馆的大厅。昨夜一场打斗点着了屋梁，火已经烧了小半个钟头——" +
                    "柜台塌了一半，地上横着烧断的木料，浓烟压在天花板下面。"
            } else {
                "「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆。眼下是深秋傍晚，屋里点了四盏油灯。"
            },
        )
        val things = listOf(
            hall,
            Entity(
                id = "charred_beam", kind = "器具", label = "烧断的屋梁",
                x = base + 20, y = 400, z = ProbeSupport.GROUND,
                attributes = mapOf("状态" to if (burned) "烧得只剩一层黑壳" else "完好"),
                sentences = listOf(
                    if (burned) "半截横梁斜插在地上，表面全是焦黑的炭，还在冒烟。" else "一根结实的屋梁。",
                ),
            ),
            Entity(
                id = "smoke", kind = "现象", label = "浓烟",
                x = base, y = 400, z = ProbeSupport.GROUND + 200,
                sentences = listOf("灰黑色的烟压在屋梁下面，几乎看不清两米以外的东西。"),
            ),
            Entity(
                id = "dwarf", kind = CHARACTER_KIND, label = "酒馆里的矮人",
                x = 1000, y = 200, z = ProbeSupport.GROUND,
                cards = listOf(
                    CharacterCard(
                        "身份",
                        "你是「醉猫」酒馆的老板杜林，矮人，五十来岁，话不多，把这家酒馆看得比自己重。",
                    ),
                ),
                cycle = Cycle(
                    listOf(
                        Beat(7 * 60, "在后厨生火、把杯子一只只擦干净，等第一个客人进门。", 1000, 200, ProbeSupport.GROUND),
                        Beat(18 * 60, "站在柜台后面招呼客人，手里那块布一直没停过。", 1000, 200, ProbeSupport.GROUND),
                    ),
                    bornAt = WorldTime(0),
                ),
            ),
            Entity(id = "player", kind = CHARACTER_KIND, label = "你", x = 1000, y = 200, z = ProbeSupport.GROUND),
        )
        return World.from(WorldSnapshot(things))
    }

    @Test
    fun doesItSayTheTavernIsGoneWhenTheTavernIsGone() = runBlocking<Unit> {
        val key = ProbeSupport.keyOrNull()
        if (key == null) {
            println("（没有 key，跳过）")
        } else {
            val sentry = SentryJudge(ProbeSupport.configOf(key))

            val cases = listOf(
                Triple("① 没事（该说做得了）", world(burned = false), "站在柜台后面招呼客人，手里那块布一直没停过。"),
                Triple("② 烧起来了（该说做不了）", world(burned = true), "站在柜台后面招呼客人，手里那块布一直没停过。"),
                Triple("③ 远得看不见（该说做得了）", world(burned = false, farAway = true), "在镇子另一头摆摊卖鱼。"),
            )

            println("═".repeat(100))
            println("哨兵：这一格现在做得了吗（每格问三次）")
            println("═".repeat(100))

            for ((label, world, state) in cases) {
                val dwarf = world.byId("dwarf")!!
                val beat = Beat(18 * 60, state, dwarf.x, dwarf.y, dwarf.z)
                println()
                println("── $label ──")
                println("   那一格：「$state」在 (${beat.x}, ${beat.y}, ${beat.z})")
                repeat(3) { i ->
                    val mark = TimeSource.Monotonic.markNow()
                    val verdict = sentry.check(world, dwarf, beat)
                    val ms = mark.elapsedNow().inWholeMilliseconds
                    println("   ${i + 1}/3 做得了=${verdict.possible}（${ms} ms）｜${verdict.reason}")
                }
            }
            println()
            println("读数只有人能读：①② 的差别就是「它认不认得火海」这件事本身。")
        }
    }
}
