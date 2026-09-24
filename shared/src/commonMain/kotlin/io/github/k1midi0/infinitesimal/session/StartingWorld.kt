package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.RuleEntry
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.Shapes

/**
 * 开局的那个世界。
 *
 * **它是内容，不是配置。** 放在代码里只是因为现在还只有这一个；等有了第二个场景，它就该搬家
 * （一个 JSON 文件，或者一张表）。那时要顺手加一条：**开局世界的合法性也得过一遍 [io.github.k1midi0.infinitesimal.judge.Committer]**
 * ——手写七个实体、坐标写错一位是迟早的事，而那种错得等到运行时才炸。
 *
 * 这里的东西**故意留着没渲染**（`sentences` 为空）：一进游戏就该看到"没见过它"的状态，
 * 玩家点一下才烧一次渲染（§7.1）。
 */
object StartingWorld {

    /**
     * 世界自己的规则书（§12 第 12 项）。**它是所有规则里最泛的一份**，默认优先级 10。
     *
     * 两条都是"这个世界怎么运转"级别的话，不是某一样东西的性质——东西的性质写在它自己的 [Entity.rulebook]
     * 里。这两句故意都很短：规则书要能一眼读完，长规则书的效果是模型开始跳读。
     */
    fun rules(): Rulebook = Rulebook(
        listOf(
            RuleEntry("火会烧起来：木头碰到火先冒烟，再烧着。"),
            RuleEntry("水往低处流，不会自己往上走。"),
        ),
    )

    /**
     * 开局那张酒馆。
     *
     * **坐标是厘米**（附录 A.2，2026-09-24 改的）。高度不再是"第几层"，而是真实的高度：桌面 80
     * 厘米，所以酒杯就在 z=80。于是「在桌上」从一个编号变成了一个**几何事实**——这正是当初要厘米
     * 的理由（附录 A.2）。
     */
    fun tavern(): List<Entity> = listOf(
        Entity(
            id = "tavern_hall",
            kind = "地点",
            label = "酒馆大厅",
            x = 1000, y = 0, z = 0,
            attributes = mapOf("地面" to "夯土", "光照" to "几盏油灯"),
            background = """
                这里是「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆，木石结构，建成约二十年。
                眼下是深秋傍晚，屋里点了四盏油灯，客人比往常少。
            """.trimIndent(),
        ),
        Entity(
            id = "oak_table",
            kind = "家具",
            label = "木桌",
            // 桌面 80 厘米高、两米长一米宽——这几句在有了形状之后会变成体素表，现在先只有位置。
            x = 1200, y = 400, z = 0,
            attributes = mapOf("材质" to "橡木", "状态" to "完好"),
            shape = Shapes.table(200, 100, 80, topCm = 10, legCm = 10),
            // 一条**具体到这张桌子上**的规则。它默认优先级 30，比世界规则（10）高——这就是"越具体
            // 越优先"落到实处的地方：世界规则说木头会烧，这张桌子的规则可以推翻它。
            rulebook = Rulebook(
                listOf(RuleEntry("这张桌子是橡木打的，结实，但怕火。", values = mapOf("硬度" to "6"))),
            ),
        ),
        Entity(
            id = "wine_cup",
            kind = "器具",
            label = "酒杯",
            // 站在桌面上：80 厘米高。
            x = 1200, y = 400, z = 80,
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒"),
            shape = Shapes.box(10, 10, 15),
        ),
        Entity(
            id = "oil_lamp",
            kind = "器具",
            label = "油灯",
            x = 1200, y = 500, z = 80,
            attributes = mapOf("材质" to "铁与玻璃", "状态" to "点着"),
            shape = Shapes.box(15, 15, 25),
        ),
        Entity(
            id = "hearth",
            kind = "家具",
            label = "壁炉",
            x = 1000, y = 0, z = 0,
            attributes = mapOf("状态" to "烧着"),
            shape = Shapes.box(150, 60, 120),
        ),
        Entity(
            id = "cellar_door",
            kind = "门窗",
            label = "地窖门",
            x = 1300, y = 100, z = 0,
            attributes = mapOf("状态" to "关着"),
            shape = Shapes.box(80, 5, 100),
        ),
        Entity(
            id = PLAYER,
            kind = "角色",
            label = "你",
            x = 1200, y = 300, z = 0,
            attributes = mapOf("两手" to "空着", "腰间" to "挂着一把短柄斧"),
            shape = Shapes.box(40, 25, 170),
            // 人设卡（§3.5）。**「交互范围」这一张就是「够不够得着」那条线的来源**——距离在这里
            // 已经是格了（写卡那一步换算，基准 1 格 = 1 米，附录 A.12），判定只读，不做算术。
            // 没有这一张卡的故事就不给这一行，由模型按情境判（A.6）。
            cards = listOf(
                CharacterCard("身份", "你是走南闯北的过路人，身上没什么钱，惯用一把短柄斧。"),
                CharacterCard("交互范围", "你伸得着大约一格以内的东西——一臂之遥。够不到就得先走过去。"),
            ),
        ),
        // **身上带着的东西也是实体**，靠 parent 挂在角色下面。这样"把手里的东西放下"就是一次
        // Reparent，不需要为"随身物品"另开一套数据结构（§3.1）。
        Entity(
            id = "axe",
            kind = "器具",
            label = "短柄斧",
            // 腰间：90 厘米。
            x = 1200, y = 300, z = 90,
            attributes = mapOf("状态" to "刃口有些卷"),
            shape = Shapes.box(30, 5, 10),
        ),
        Entity(
            id = "rag",
            kind = "器具",
            label = "抹布",
            x = 1200, y = 300, z = 90,
            attributes = mapOf("状态" to "半干"),
            shape = Shapes.box(25, 25, 5),
        ),
    )
}
