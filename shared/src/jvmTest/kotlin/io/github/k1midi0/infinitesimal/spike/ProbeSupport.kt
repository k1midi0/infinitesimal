package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.world.Entity
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

    fun seed(path: String, entities: List<Entity>): World =
        World.from(WorldSnapshot(entities)).also { save(path, it) }

    fun save(path: String, world: World) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(WorldSnapshot.serializer(), world.snapshot()))
    }
}
