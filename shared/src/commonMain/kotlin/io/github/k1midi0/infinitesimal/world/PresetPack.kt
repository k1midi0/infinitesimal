package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * **出厂预设包的那个文件格式**（§12 第 36 项）。
 *
 * ## 为什么是网格文本，不是序列化出来的 `VoxelShape`
 *
 * 直接序列化 `VoxelShape` 更省事（它本来就是 `@Serializable`），但产物是一堆坐标三元组：读不出来、
 * diff 不出东西、而且体积大一倍（一个格 `{"x":195,"y":195,"z":5}` 是二十几个字节，网格文本里它是
 * 一个字符）。
 *
 * [Entry.grid] 存的正是建模那边本来就印出来的那种网格（[ShapeGrid.render]），于是它有两个好处：
 * **人看得懂**（一份形状好不好，看一眼网格就知道，这与探针里回画网格是同一个理由），以及**与那些
 * 工具共用一个格式**——[ShapeGrid.parse] 已经存在，校验也一并免费。
 *
 * 代价是加载时要解析一次。一次而已，进游戏之前。
 */
@Serializable
data class PresetPack(
    val presets: List<Entry>,
) {
    /**
     * 一条预设的线上形状。
     *
     * 它**不是** [Preset]：那边拿的是解析好的 [VoxelShape]，这边拿的是一段文本。两者分开是有意的
     * ——文件里存的是数据，进内存之前要过一遍校验；把两者合成一个类，就得让 [Preset] 允许"一份还没
     * 解析的形状"，而那是一个能一路带到世界里的坏状态。
     */
    @Serializable
    data class Entry(
        val name: String,
        val brief: String,
        val category: String = "",
        val tags: List<String> = emptyList(),
        val grid: String,
    )
}

/** 把一条预设写成文件里那一份。 */
fun Preset.toEntry(): PresetPack.Entry = PresetPack.Entry(
    name = name,
    brief = brief,
    category = category,
    tags = tags,
    grid = ShapeGrid.render(shape),
)

/**
 * 把文件里那一份读成一条预设。
 *
 * **读不成网格就抛**，不静默丢掉这一条：出厂包里少一条的表现是"这东西一直在现造"（几十秒一次、而且
 * 不报错），把它变成一个能在启动时就看见的错误，是这里唯一值得做的事。
 */
fun PresetPack.Entry.toPreset(): Preset = when (val parsed = ShapeGrid.parse(grid)) {
    is ShapeGrid.Parsed.Ok -> Preset(
        name = name,
        brief = brief,
        shape = parsed.shape,
        origin = Preset.Origin.BUILTIN,
        category = category,
        tags = tags,
    )

    is ShapeGrid.Parsed.Bad -> error(
        "出厂预设包里的「$name」读不成形状：${parsed.problems.joinToString("；")}",
    )
}
