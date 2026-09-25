package io.github.k1midi0.infinitesimal.generate

import io.github.k1midi0.infinitesimal.llm.PropertySchema
import io.github.k1midi0.infinitesimal.llm.ToolDefinition
import io.github.k1midi0.infinitesimal.llm.int
import io.github.k1midi0.infinitesimal.llm.text
import io.github.k1midi0.infinitesimal.llm.tool

/**
 * 生成器的七只手（A.16、§14.30）。
 *
 * | 手 | 干什么 | 坐标单位 |
 * |---|---|---|
 * | `fill_terrain` / `dig_terrain` / `replace_terrain` | 框选一大片，铺 / 挖 / 换一种材质 | 地形格 |
 * | `look_terrain` | 框选一块，读回它现在长什么样 | 地形格 |
 * | `edit_terrain` | 把读回来的那张表改几个字符发回去 | 地形格 |
 * | `find_preset` | 按关键词在预设库里找现成的东西 | —— |
 * | `make_object` | 在地面上放一样东西 | **厘米** |
 *
 * ## 为什么框选那三只与 `make_object` 的单位不一样
 *
 * 这不是疏忽，是两件事**本来就用两种尺度**：地形一格 50 厘米、一张地图是几百格见方，用厘米说一块地
 * 是六个七八位数；而一样东西在地面上只占几十厘米，用地形格说就是「零点几格」。判定那边早就是这个
 * 分法（地形用格、实体用厘米），生成器沿用同一套，模型只需要记「改地皮用格、放东西用厘米」这一句。
 *
 * 提示词里因此把这句话写了两遍——一次在规矩里，一次在那一轮的末尾。
 *
 * ## 为什么找东西要单独一只手
 *
 * 实测（§14.31）：一次生成里同一个东西被造了 11 遍，因为 `make_object` 的 `brief` 是**逐字匹配**，
 * 而模型每次写的要求都略微不同。让"找"成为一个显式动作，模型才有机会在放东西之前先看看库里有什么
 * ——这与「说话必须是显式动作」（A.2）是同一条思路：**看不见的东西，模型不会去用**。
 */
object GeneratorTools {

    const val FILL_TERRAIN = "fill_terrain"
    const val DIG_TERRAIN = "dig_terrain"
    const val REPLACE_TERRAIN = "replace_terrain"
    const val LOOK_TERRAIN = "look_terrain"
    const val EDIT_TERRAIN = "edit_terrain"
    const val FIND_PRESET = "find_preset"
    const val MAKE_OBJECT = "make_object"

    /**
     * 六个坐标。
     *
     * **故意不给默认值**：给 0 的话，漏填一个角就会静默地变成一根竖井，而它是个**合法的**框——
     * 校验放它过去，世界长出一条没道理的柱子，而谁都不会报错。
     */
    private fun box(): Array<Pair<String, PropertySchema>> = arrayOf(
        int("x1", "框的一个角。单位是**地形格**（50 厘米一格），不是厘米"),
        int("y1", "那个角的 y。地形格"),
        int("z1", "那个角的 z。地形格。地面大致在第 256 格上下"),
        int("x2", "对角。与 x1 谁大谁小都行"),
        int("y2", "对角的 y"),
        int("z2", "对角的 z"),
    )

    private val FILL_TOOL = tool(
        FILL_TERRAIN,
        "**框选一大片，铺上一种材质。**这是铺地面的主力：一层草、一片石板、一整块山地。" +
            "一次可以盖住好几块 chunk，别一块一块地铺。框里的每一格都会被填成这个材质。",
        *box(),
        text("material", "材质 id（见提示词里的材质表）：soil 土 / stone 石 / bedrock 岩层 / " +
            "wood 木地板 / grass 草 / water 水 / road 路面"),
    )

    private val DIG_TOOL = tool(
        DIG_TERRAIN,
        "**框选一大片，挖空。**挖掉的是「没有材质」这件事本身——地下会变成空腔，地面会凹下去。" +
            "挖一条沟、掏一个洞用它。挖到挖不动的材质会被拦下（岩层）。",
        *box(),
    )

    private val REPLACE_TOOL = tool(
        REPLACE_TERRAIN,
        "**框里所有 `from` 那种材质换成 `to`**，别的材质原样留着。这是「烧焦一片草地」「把石板路" +
            "掀成土路」——它只动一种，所以比先挖后铺少一次改动。",
        *box(),
        text("from", "要换掉的材质 id"),
        text("to", "换成什么材质 id"),
    )

    private val LOOK_TOOL = tool(
        LOOK_TERRAIN,
        "**框选一块，读回它现在的样子**——一张分层的字符表，每格一个材质字符。" +
            "改一块地方之前先看它。这是唯一一只**不落地**的手：看完接着干活。",
        *box(),
    )

    private val EDIT_TOOL = tool(
        EDIT_TERRAIN,
        "**把 `look_terrain` 读回来的那张表改几个字符发回来**，以同一个框为最大边界。" +
            "表必须与框严格同尺寸：层数、每层行数、每行字符数都要对上，一层都不能省。" +
            "`.` 是挖空。雕一个台阶、挖一条窄沟用这只；铺大片用框选那三只。",
        *box(),
        text("table", "完整的表，从 `z=<地形格号>` 起，每层若干行"),
    )

    private val FIND_TOOL = tool(
        FIND_PRESET,
        "**按关键词在预设库里找现成的东西。**写几个你想到它的词（「橡树」「木桌」「灯」），" +
            "拿回一张候选清单：名字、类别、标签、当初那句要求。" +
            "放东西之前先搜一次——**库里有现成的就用现成的**，没有的才值得现造一份（现造要花几十秒）。" +
            "这是不落地的手：搜完接着干活。",
        text("keywords", "几个词，用顿号或空格分开。写材质、形状、用途、像什么，别写整句话"),
        text("category", "可选。只要某一类的，例如 树木、家具。不确定就留空，留空是按关键词搜所有"),
    )

    private val MAKE_TOOL = tool(
        MAKE_OBJECT,
        "**在地面上放一样东西。**树、灌木、石头、一只野兽都算。" +
            "它先拿 brief 去预设库里找**逐字相同的那一条**；库里没有，它会照着这句 brief 现造一份" +
            "形状再放上去（几十秒）。所以：**先 `find_preset` 看看库里有什么**——搜到合适的，把它的" +
            "名字填进 `preset`，或者把它那句要求逐字抄进 `brief`，那就一分钱不花；" +
            "库里真的没有，才自己写一句新要求，而那时 brief 要写成**一句能独立读懂的要求**——" +
            "造形状的那个模型看不到你看到的这一切。",
        text("id", "给这样东西起的 id。**一局里唯一**，英文小写加下划线"),
        text("kind", "这是哪一类东西，例如 tree / bush / rock / beast"),
        text("label", "界面上给人看的名字，中文"),
        text("brief", "一句能独立读懂的要求：这是什么、多大、什么样子"),
        int("x", "放在哪。单位是**厘米**（不是地形格），下面两个同"),
        int("y", "厘米"),
        int("z", "厘米。站在地面上就是地面高度，本局地面在 12800 附近"),
        text("preset", "可选。`find_preset` 找到的那条的名字。填了就用它，不再看 brief"),
        optional = listOf("preset"),
    )

    /** 一次全部给它。**生成不分步**：铺地与放东西是同一件事的两半（A.16）。 */
    val ALL: List<ToolDefinition> = listOf(
        FILL_TOOL,
        DIG_TOOL,
        REPLACE_TOOL,
        LOOK_TOOL,
        EDIT_TOOL,
        FIND_TOOL,
        MAKE_TOOL,
    )

    /**
     * **不落地**的那两只：调完之后这一轮还要接着往下走。
     *
     * `look_terrain` 与 `find_preset` 都是"看一眼"，它们的返回值是接着干活要用的输入，不是结果本身。
     */
    fun isQuery(name: String): Boolean = name == LOOK_TERRAIN || name == FIND_PRESET
}
