package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.schedule.SentryJudge
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TerrainBox
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
 *  2. **柜台塌了**（要站的那个位置还在，但"站在柜台后面招呼客人"要用的柜台已经塌成一堆烧黑的
 *     木料）→ 该说"做不了"，而且理由里要指着柜台说。**这一条是第二遍读数改出来的**（§14.45）：
 *     第一版把烧起来的东西摆在**别处**，而那是一个正确读法——远处一根烧断的屋梁不妨碍它在柜台后
 *     照常招呼客人。判据必须落在**这件事自己身上**。
 *  3. **落点被填死了**（原地埋了一块石头，那一格站不住）→ 也该说"做不了"，理由指那块石头。
 *
 * 次数小（三格各三次）。它要的不是统计，而是"这条路真的通"——模型在这件事上会不会答非所问。
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*SentryProbe*" --rerun `
 *   -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 * ```
 */
class SentryProbe {

    /**
     * [counterRuined] ＝ 柜台塌成一堆烧黑的木料；[buried] ＝ 那个落点被石头填死。
     *
     * 两者都**只改这一件事自己的证据**：一个是它要用的那件东西，一个是它的落点。
     */
    private fun world(counterRuined: Boolean, buried: Boolean = false, farAway: Boolean = false): World {
        val base = if (farAway) 900_000 else 1200
        val hall = Entity(
            id = "tavern_hall", kind = "地点", label = "酒馆大厅",
            x = base, y = 400, z = ProbeSupport.GROUND,
            attributes = mapOf("地面" to "夯土", "光照" to "几盏油灯"),
            background = if (counterRuined) {
                "「醉猫」酒馆的大厅。方才一场打斗掀翻了柜台，火就是从柜台那儿烧起来的——柜台塌成" +
                    "一堆烧黑的木料，浓烟压在天花板下面，压得人睁不开眼。"
            } else {
                "「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆。眼下是深秋傍晚，屋里点了四盏油灯。"
            },
        )
        // 柜台：这一格说的那件事（站在柜台后面招呼客人）**要用的就是它**。
        val counter = if (counterRuined) {
            Entity(
                id = "counter", kind = "器具", label = "塌成一堆的柜台",
                x = 1000, y = 200, z = ProbeSupport.GROUND,
                attributes = mapOf("状态" to "塌了，烧剩半截"),
                sentences = listOf("柜台已经塌成一堆烧黑的木料，台面整块倒在地上，没有一处还立着。"),
            )
        } else {
            Entity(
                id = "counter", kind = "器具", label = "柜台",
                x = 1000, y = 200, z = ProbeSupport.GROUND,
                attributes = mapOf("状态" to "完好，台面擦得发亮"),
                sentences = listOf("一张厚实的橡木柜台，台面被擦得发亮。"),
            )
        }
        val things = buildList {
            add(hall)
            add(counter)
            add(
                Entity(
                    id = "lamp", kind = "器具", label = "油灯",
                    x = 1200, y = 500, z = ProbeSupport.GROUND + 150,
                    sentences = listOf("一盏铜座油灯，火苗安安静静地立着。"),
                ),
            )
            if (counterRuined) {
                add(
                    Entity(
                        id = "charred_beam", kind = "器具", label = "烧断的屋梁",
                        x = base + 20, y = 400, z = ProbeSupport.GROUND,
                        attributes = mapOf("状态" to "烧得只剩一层黑壳"),
                        sentences = listOf("半截横梁斜插在地上，表面全是焦黑的炭，还在冒烟。"),
                    ),
                )
                add(
                    Entity(
                        id = "smoke", kind = "现象", label = "浓烟",
                        x = base, y = 400, z = ProbeSupport.GROUND + 200,
                        sentences = listOf("灰黑色的烟压在屋梁下面，几乎看不清两米以外的东西。"),
                    ),
                )
            }
            add(
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
            )
            add(Entity(id = "player", kind = CHARACTER_KIND, label = "你", x = 1000, y = 200, z = ProbeSupport.GROUND))
        }
        val world = World.from(WorldSnapshot(things))
        if (buried) {
            // 那个落点（脚底到头上两层）被一块岩体填死。哨兵要读的是「落点站不住」，而清单里会看到
            // 这一块实体；材质用表里第一种，它是哪块石头不重要。
            world.terrain.fill(
                TerrainBox(20, 4, 256, 21, 5, 258),
                world.materials.all.first().id,
                world.materials,
            )
            world.put(
                Entity(
                    id = "rockfall", kind = "地形", label = "堵住那一处的岩体",
                    x = 1000, y = 200, z = ProbeSupport.GROUND,
                    sentences = listOf("一块新塌下来的岩体把那处空间从地到顶填满了。"),
                ),
            )
        }
        return world
    }

    @Test
    fun doesItSayTheTavernIsGoneWhenTheTavernIsGone() = runBlocking<Unit> {
        val key = ProbeSupport.keyOrNull()
        if (key == null) {
            println("（没有 key，跳过）")
        } else {
            val sentry = SentryJudge(ProbeSupport.configOf(key))

            val cases = listOf(
                Triple("① 没事（该说做得了）", world(counterRuined = false), "站在柜台后面招呼客人，手里那块布一直没停过。"),
                Triple("② 柜台塌了（该说做不了）", world(counterRuined = true), "站在柜台后面招呼客人，手里那块布一直没停过。"),
                Triple("③ 落点被填死（该说做不了）", world(counterRuined = false, buried = true), "站在柜台后面招呼客人，手里那块布一直没停过。"),
                Triple("④ 远得看不见（该说做得了）", world(counterRuined = false, farAway = true), "在镇子另一头摆摊卖鱼。"),
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
            println("读数只有人能读：① 该说做得了，②③ 该说做不了——而 ②③ 各自要用清单里那件东西说话。")

            // **哨兵眼前那一片**（dry run）：读数不好看时，第一件要问的事是"那些证据到底进没进它
            // 眼前那一段"。看不到就不是它的判断力问题，而是这一片的范围（第二遍读数就是这么读的）。
            for ((label, w) in listOf("柜台塌了" to world(counterRuined = true), "落点埋了" to world(counterRuined = false, buried = true))) {
                val dwarf = w.byId("dwarf")!!
                println()
                println("【哨兵眼前那一片（$label，dry run）】")
                println(
                    io.github.k1midi0.infinitesimal.spatial.RegionView.render(
                        w,
                        io.github.k1midi0.infinitesimal.spatial.RegionBuilder.of(w, listOf(dwarf.id)),
                        dwarf.id,
                    ),
                )
            }
        }
    }
}
