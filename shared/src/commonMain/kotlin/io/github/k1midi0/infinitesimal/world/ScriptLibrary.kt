package io.github.k1midi0.infinitesimal.world

/**
 * **参考剧本库**（§14.55）——第一层的那一份东西。
 *
 * ## 它为什么是一张有序的表，而不是"出厂 + 生成"两个包
 *
 * 预设库（[PresetLibrary]）分两档，是因为那两档的**来路**不同：出厂的是随游戏带来的形状，生成的是一局
 * 里长出来的。参考剧本不分：**它们全都是"世界之外的素材"**，从资源文件里读来的、从存档里读回来的，
 * 是同一类东西——差别只在"这一份是随游戏带来的还是你上次读过的那一份"。
 *
 * 所以要保的是**顺序**（[LinkedHashMap]）：清单进提示词，而"库里第几条"读起来该是稳定的。
 *
 * ## [restore] 是合并不是清空
 *
 * 存档那一侧要小心：出厂那份是**在存档之外**的（它在资源文件里，读档的时候已经装进这个库了）。若
 * [restore] 清空重填，那么**一份没有参考剧本的旧档会把出厂那份一起清掉**——而那件事不会报错，只会让
 * 导演从此看不见任何参考剧本。所以它是合并且**存档那份按 id 覆盖**：同一条以存档里的为准（那是当时
 * 的事实），出厂那份额外的照旧留着。
 *
 * 而**没有任何一条路会删掉一条参考剧本**：库是"素材"，删它没有语义（世界里那份填出来的剧本才是会被
 * 改写、会被完结的东西）。所以合并不会丢掉谁。
 */
class ScriptLibrary(reference: List<ReferenceScript> = emptyList()) {

    private val byId = LinkedHashMap<String, ReferenceScript>()

    init {
        reference.forEach { byId[it.id] = it }
    }

    /** 库里所有参考剧本，按进来的先后。 */
    fun all(): List<ReferenceScript> = byId.values.toList()

    /** 库里所有代号。**`write_script` 的 `source` 拿它当判据**（一个打错的来源不该静默地指不到原典）。 */
    fun ids(): Set<String> = byId.keys.toSet()

    fun find(id: String): ReferenceScript? = byId[id.trim()]

    val size: Int get() = byId.size

    /** 记下一条（离线工具、或者运行时导入用）。同一个 id 的第二条**替换**第一条——id 就是它的身份。 */
    fun learn(script: ReferenceScript) {
        byId[script.id] = script
    }

    /**
     * 存档读回来时用。**合并，不清空**——见类注释里那段"旧档会把出厂那份一起清掉"。
     */
    fun restore(scripts: List<ReferenceScript>) {
        scripts.forEach { byId[it.id] = it }
    }

    /**
     * **还没被填成这个世界的线**的那些（§14.55）。
     *
     * 判据是世界里那些剧本的 `source`：已经有哪一条以它为自己的来源，那一条就算填过了。**不另存一张
     * "已用"的表**——那张表一旦与池子漂开，就会出现"库里说没填过、池子里明明有一条"的两个真相。
     */
    fun pendingIn(world: World): List<ReferenceScript> {
        val used = world.scripts.mapNotNull { it.source }.toSet()
        return all().filterNot { it.id in used }
    }
}
