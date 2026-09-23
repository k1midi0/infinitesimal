package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity

/**
 * 开局的那个世界。
 *
 * **它是内容，不是配置。** 放在代码里只是因为现在还只有这一个；等有了第二个场景，它就该搬家
 * （一个 JSON 文件，或者一张表）。那时要顺手加一条：**开局世界的合法性也得过一遍 [io.github.k1midi0.infinitesimal.judge.Committer]**
 * ——手写七个实体，`parent` 写错一个字母是迟早的事，而那种错得等到运行时才炸。
 *
 * 这里的东西**故意留着没渲染**（`sentences` 为空）：一进游戏就该看到"没见过它"的状态，
 * 玩家点一下才烧一次渲染（§7.1）。
 */
object StartingWorld {

    fun tavern(): List<Entity> = listOf(
        Entity(
            id = "tavern_hall",
            kind = "地点",
            label = "酒馆大厅",
            x = 10, y = 0, z = 0,
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
            x = 12, y = 4, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("材质" to "橡木", "状态" to "完好"),
        ),
        Entity(
            id = "wine_cup",
            kind = "器具",
            label = "酒杯",
            x = 12, y = 4, z = 1,
            parent = "oak_table",
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒"),
        ),
        Entity(
            id = "oil_lamp",
            kind = "器具",
            label = "油灯",
            x = 12, y = 5, z = 1,
            parent = "oak_table",
            attributes = mapOf("材质" to "铁与玻璃", "状态" to "点着"),
        ),
        Entity(
            id = "hearth",
            kind = "家具",
            label = "壁炉",
            x = 10, y = 0, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("状态" to "烧着"),
        ),
        Entity(
            id = "cellar_door",
            kind = "门窗",
            label = "地窖门",
            x = 13, y = 1, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("状态" to "关着"),
        ),
        Entity(
            id = PLAYER,
            kind = "角色",
            label = "你",
            x = 12, y = 3, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("两手" to "空着", "腰间" to "挂着一把短柄斧"),
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
            x = 12, y = 3, z = 1,
            parent = PLAYER,
            attributes = mapOf("状态" to "刃口有些卷"),
        ),
        Entity(
            id = "rag",
            kind = "器具",
            label = "抹布",
            x = 12, y = 3, z = 1,
            parent = PLAYER,
            attributes = mapOf("状态" to "半干"),
        ),
    )
}
