package io.github.k1midi0.infinitesimal.world

/**
 * 世界状态：唯一的事实（§1.1）。
 *
 * 它**只做状态**——不碰模型、不碰文件、不碰界面。渲染器读它，判定与导演写它，而所有写入都从这里
 * 经过，所以"世界里到底有什么"永远只有一个答案。
 *
 * 现在有七种东西：[Entity]（事实）、[log]（记录）、[clock]（世界时间）、[timeline]（未来的事，
 * §8.1）、[terrain]（地形层）与 [zoning]（区划层），外加一张只读的 [materials]（材质表）。体素形状表
 * 还不是独立的一种——它挂在实体自己的 [Entity.shape] 上。
 *
 * **[log] 与前六种不是一类。** §2 那三层里，前六种是「事实」（世界现在什么样，可以被改写），只有它是
 * 「记录」（发生过什么，永不改写）。把它们混在一处思考，就会有人顺手在 [remove] 里也删掉记录。
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
    /**
     * 世界时间（§8.1）。
     *
     * **它在 world 层而不是 session 层**：时间是世界的事实之一——"现在是第几天傍晚"与"桌上有一把
     * 斧子"是同一类东西，两者都必须存下来、都必须只有一个答案。`session/` 做的是**推进**它，不是
     * 拥有它。
     */
    var clock: WorldTime = StartingClock,
    /**
     * 时间轴：未来的事（§8.1）。导演写它，快进读它。
     */
    val timeline: Timeline = Timeline.empty(),
    /**
     * 材质表（附录 A.15）：按 id 查的一张只读表。**它与预设库并列，不是预设的一种。**
     */
    val materials: MaterialTable = MaterialTable(),
    /**
     * 地形层（附录 A.15）：一格一个材质 id，稀疏存。
     *
     * **它与实体表不重叠**，因为物体在地形**表面之上**——地板物体、桌上的东西，都在地形之上，
     * 不是埋在它里面。
     */
    val terrain: Terrain = Terrain.empty(),
    /**
     * 区划层（附录 A.16）：导演的涂鸦，但它是**世界事实**，不是另存的一份笔记（§3.2.4）。
     */
    val zoning: Zoning = Zoning.empty(),
    /**
     * **记录层**（§1.5、§6.1、§12 第 20 项）：发生过什么。
     *
     * 它与 [entities] 是两份不同的东西，这是刻意的：事实是"世界现在什么样"（可以被改写），记录是
     * "发生过什么"（**永不改写**）。所以 [remove] 一个实体不会动这里，而"这个角色收到过哪几条"也
     * 不挂在实体上——角色死了，它收到过什么仍然是记录（§3.2.2）。
     */
    val log: EventLog = EventLog.empty(),
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

    fun snapshot(): WorldSnapshot = WorldSnapshot(
        entities = entities.values.toList(),
        rulebook = rulebook,
        clock = clock,
        timeline = timeline.snapshot(),
        materials = materials,
        terrain = terrain.snapshot(),
        zoning = zoning.snapshot(),
        log = log.snapshot(),
    )

    companion object {

        /**
         * 开局那一刻：**第 1 天 18:00**。
         *
         * 它是**内容**，不是配置——开局那张酒馆写的是"深秋傍晚、屋里点着四盏油灯"（[Rules][StartingWorld]），
         * 时间就得是傍晚。两处对不上，模型第一句话就会露馅。
         */
        val StartingClock: WorldTime = WorldTime.of(day = 1, hour = 18)

        fun empty(): World = World(mutableMapOf())

        fun from(snapshot: WorldSnapshot): World = World(
            snapshot.entities.associateByTo(mutableMapOf()) { it.id },
            snapshot.rulebook,
            snapshot.clock,
            Timeline.from(snapshot.timeline),
            snapshot.materials,
            Terrain.from(snapshot.terrain),
            Zoning.from(snapshot.zoning),
            EventLog.from(snapshot.log),
        )
    }
}
