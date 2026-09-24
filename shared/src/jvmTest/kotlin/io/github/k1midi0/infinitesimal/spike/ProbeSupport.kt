package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 探针共用那几样：读密钥、把世界落到文件。
 *
 * 产品里的 `world/` **不做持久化**——存档是 `session/` 的事，所以它没有平台依赖。文件读写因此留在
 * 探针这一侧：探针是一次性的验收脚手架，它要能用最少的字把世界存下来看一眼。
 */
object ProbeSupport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 只读密钥，不打印它。返回 null 表示这次没提供——探针应当跳过而不是失败。 */
    fun keyOrNull(): String? = System.getProperty("infinitesimal.liveKeyFile")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.isFile }
        ?.readText()
        ?.trim()

    fun configOf(apiKey: String): EndpointConfig = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = apiKey,
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    /**
     * 第二步的思考强度，由 `-PinfinitesimalEffort=LOW|HIGH|MAX` 传。
     *
     * **不传就是 `null`**，也就是让判定用它自己的默认（`Judge.effort`）。这里不复制那份默认值——
     * 复制出来的常量总有一天会与产品里的那个不一致，而采样表上不会显示出来。
     */
    fun effortFromCommandLine(): ReasoningEffort? {
        val raw = System.getProperty("infinitesimal.effort")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { ReasoningEffort.valueOf(raw.uppercase()) }.getOrNull()
        if (parsed == null) println("[警告] 认不出 -PinfinitesimalEffort=$raw，按产品默认跑")
        return parsed
    }

    fun seed(path: String, entities: List<Entity>, rulebook: Rulebook = Rulebook()): World =
        World.from(WorldSnapshot(entities, rulebook)).also { save(path, it) }

    /**
     * 判定探针共用的那个世界：酒馆大厅里一张木桌，桌上一只酒杯一盏油灯，玩家身上一把斧子一块抹布。
     *
     * 放在这里而不是各自私有，是因为**读数要能横向比**：两个探针的世界只要差一样东西，两次读数就
     * 不能放在同一张表里。
     *
     * **坐标是厘米**（附录 A.2）。它与 `StartingWorld.tavern()` 不是同一份——探针这边的几样东西
     * 带着已经渲染好的句子，为的是让渲染那一步不必联网。
     */
    fun tavernSeed(): List<Entity> = listOf(
        Entity(
            id = "tavern_hall", kind = "地点", label = "酒馆大厅", x = 1000, y = 0, z = 0,
            attributes = mapOf("地面" to "夯土", "光照" to "几盏油灯"),
            background = """
                这里是「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆，木石结构，建成约二十年。
                眼下是深秋傍晚，屋里点了四盏油灯，客人比往常少。
            """.trimIndent(),
        ),
        Entity(
            id = "oak_table", kind = "家具", label = "木桌", x = 1200, y = 400, z = 0,
            attributes = mapOf("材质" to "橡木", "状态" to "完好"),
            sentences = listOf("橡木桌呈深褐色，表面打磨得光滑，木纹清晰而紧密地延展。"),
        ),
        Entity(
            id = "wine_cup", kind = "器具", label = "酒杯", x = 1200, y = 400, z = 80,
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒"),
            sentences = listOf("深色木制杯身，表面可见木纹，杯口边缘有几道划痕。"),
        ),
        Entity(
            id = "oil_lamp", kind = "器具", label = "油灯", x = 1200, y = 500, z = 80,
            attributes = mapOf("材质" to "铁与玻璃", "状态" to "点着"),
            sentences = listOf("铁架上扣着一只发黄的玻璃罩，罩口有烟熏的痕迹。"),
        ),
        Entity(
            id = "player", kind = "角色", label = "你", x = 1200, y = 300, z = 0,
            attributes = mapOf("两手" to "空着", "腰间" to "挂着一把短柄斧"),
        ),
        Entity(
            id = "axe", kind = "器具", label = "短柄斧", x = 1200, y = 300, z = 90,
            attributes = mapOf("状态" to "刃口有些卷"),
        ),
        Entity(
            id = "rag", kind = "器具", label = "抹布", x = 1200, y = 300, z = 90,
            attributes = mapOf("状态" to "半干"),
        ),
    )

    fun save(path: String, world: World) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(WorldSnapshot.serializer(), world.snapshot()))
    }
}
