package io.github.k1midi0.infinitesimal.spike

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * spike 用的最小世界：一张实体表 + 一个 JSON 文件。
 *
 * 它**不是**要成为产品里的 `world/`——这里只放验那个循环所必需的字段。设计里那些（体素形状表、
 * 关系、时间轴、覆盖层）一律没有，因为 spike 要回答的问题不含它们：**"渲染即固化"这个循环跑不跑
 * 得动，延迟和成本是多少。**
 */
@Serializable
data class SpikeEntity(
    val id: String,
    val kind: String,
    val label: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val parent: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    /** 固化下来的描述。null = 从没被观察过，或者已经失效。 */
    val description: String? = null,
    /** 观察精度。0 = 粗略，每细化一次 +1。 */
    val detailLevel: Int = 0,
)

@Serializable
data class SpikeWorldFile(val entities: List<SpikeEntity>)

class SpikeWorld(private val file: File) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var entities: MutableList<SpikeEntity> = mutableListOf()

    fun load(): SpikeWorld {
        entities = if (file.isFile) {
            json.decodeFromString<SpikeWorldFile>(file.readText()).entities.toMutableList()
        } else {
            mutableListOf()
        }
        return this
    }

    fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(SpikeWorldFile(entities)))
    }

    fun seed(seed: List<SpikeEntity>) {
        entities = seed.toMutableList()
        save()
    }

    fun all(): List<SpikeEntity> = entities.toList()

    fun byId(id: String): SpikeEntity? = entities.firstOrNull { it.id == id }

    fun put(entity: SpikeEntity) {
        val index = entities.indexOfFirst { it.id == entity.id }
        if (index >= 0) entities[index] = entity else entities += entity
        save()
    }

    /**
     * 物体变了、或者被摧毁了：固化作废。
     *
     * 设计里的失效有三个条件（变化 / 摧毁 / 要求更精细），这条覆盖前两个——它们在"要不要重新
     * 渲染"这件事上是同一件事。第三个条件是调用方带着 refine = true，不走这里。
     */
    fun invalidate(id: String) {
        byId(id)?.let { put(it.copy(description = null, detailLevel = 0)) }
    }
}
