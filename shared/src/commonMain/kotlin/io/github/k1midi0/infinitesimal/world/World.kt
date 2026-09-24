package io.github.k1midi0.infinitesimal.world

/**
 * 世界状态：唯一的事实（§1.1）。
 *
 * 它**只做状态**——不碰模型、不碰文件、不碰界面。渲染器读它，判定与导演写它，而所有写入都从这里
 * 经过，所以"世界里到底有什么"永远只有一个答案。
 *
 * 目前只有 [Entity] 一种东西。角色、时间轴、体素形状表都还没进来，它们会在有真实需要时加，而不是
 * 先摆好空壳。
 */
class World private constructor(
    private var entities: MutableMap<String, Entity>,
    /**
     * 世界自己的规则书（§12 第 12 项）。**它比任何实体那份都低**——世界规则最泛。
     *
     * 它和 [Entity.rulebook] 一样是**判定只读的依据**，不随动作变化。判定能不能改它，就是"世界的
     * 物理法则能不能被一次动作改掉"，答案是不。
     */
    var rulebook: Rulebook = Rulebook(),
) {

    fun all(): List<Entity> = entities.values.toList()

    fun byId(id: String): Entity? = entities[id]

    /** 直接落一个实体（存在就覆盖）。它**不改描述**，所以只该用在"新建"或"改属性"上。 */
    fun put(entity: Entity) {
        entities[entity.id] = entity
    }

    /**
     * 外表变化：**形状不变**，描述作废（§7.1）。
     *
     * 这是"擦干净桌子"那条路。id 不变——它还是原来那张桌子，只是如今看上去不一样了。
     */
    fun invalidateAppearance(id: String) {
        entities[id]?.let { entities[id] = it.copy(sentences = emptyList()) }
    }

    /**
     * 彻底变化：删掉旧的，换上新的（§7.1）。
     *
     * **两件事在一次调用里做完**，中间态不可见——否则会出现"旧的没了、新的还没来"那一瞬，而快照
     * 恰好落在那一瞬就会丢掉一个物体。
     *
     * 调用方负责给新实体一个**新的 id**；这里校验它确实不同，因为"换成一个同 id 的东西"意味着
     * 调用方把 [ChangeLevel.APPEARANCE] 误当成了 [ChangeLevel.REPLACED]。
     */
    fun replace(oldId: String, replacement: Entity) {
        require(replacement.id != oldId) {
            "彻底变化必须换新 id（旧 $oldId）；同 id 的改动是外表变化，该走 invalidateAppearance"
        }
        require(entities.containsKey(oldId)) { "世界里没有 $oldId，无法替换" }
        entities.remove(oldId)
        entities[replacement.id] = replacement
    }

    /** 物体被摧毁：从世界态里消失。记录不在这里删（§1.5 记录永不改写）。 */
    fun remove(id: String) {
        entities.remove(id)
    }

    fun snapshot(): WorldSnapshot = WorldSnapshot(entities.values.toList(), rulebook)

    companion object {

        fun empty(): World = World(mutableMapOf())

        fun from(snapshot: WorldSnapshot): World =
            World(snapshot.entities.associateByTo(mutableMapOf()) { it.id }, snapshot.rulebook)
    }
}
