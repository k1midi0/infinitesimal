package io.github.k1midi0.infinitesimal.spike

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * spike 用的最小世界：一张实体表 + 一个 JSON 文件。
 *
 * 它**不是**产品里的 `world/`——体素形状表、关系、时间轴、覆盖层一律没有，因为 spike 要回答的问题
 * 不含它们。
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
    /**
     * 地点背景，**由导演写**，渲染器只读。
     *
     * 它写在地点实体上（通常是 `parent` 链的根），物体通过链继承。这样它落在**地图**里，而不是落在
     * 某个"背景表"里——世界只有地图这一份事实（§3.2.4）。
     */
    val background: String? = null,
    /**
     * 固化下来的描述，**按句存**。
     *
     * 存成一整段文本的话，"只增不改"只能靠提示词去求——而实测证明求不到（见 README 记的那次
     * spike）。存成句子的列表，它就变成数据模型的性质：**唯一的写操作是在两句之间插入**，已有的
     * 句子根本没有被改的机会。
     */
    val sentences: List<String> = emptyList(),
) {
    /** 展示用的整段文字。句子里带着各自的句末标点，所以直接相连即可。 */
    fun text(): String = sentences.joinToString("")
}

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
     * 渲染"这件事上是同一件事。第三个条件走的是 [SentenceWriter]，不在这里。
     */
    fun invalidate(id: String) {
        byId(id)?.let { put(it.copy(sentences = emptyList())) }
    }
}
