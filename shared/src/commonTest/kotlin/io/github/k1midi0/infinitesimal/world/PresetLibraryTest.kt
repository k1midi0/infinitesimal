package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 预设库那几条纪律（附录 A.9）。
 *
 * 最要紧的一条是最后那个：**工厂不是引用**。它在这里能测，是因为形状是不可变的——"打破一个杯子不能
 * 让所有杯子一起碎"因此不是靠纪律维持的，而是**在数据模型上无法被违反**。测试钉住的是这条性质，
 * 而不只是"拷贝了一份"。
 */
class PresetLibraryTest {

    private val boardShape = VoxelShape(
        solid = listOf(Voxel(0, 0, 0), Voxel(5, 0, 0), Voxel(0, 5, 0), Voxel(5, 5, 0)),
    )
    private val brief = "一块两格见方的木板"
    private val board = Preset(name = "board", brief = brief, shape = boardShape)

    @Test
    fun aRequirementFindsThePresetItBuilt() {
        val library = PresetLibrary()
        library.learn(board)

        assertEquals(board, library.byBrief(brief))
        // 前后空白不算区别：要求是从提示词里抄过来的，抄多一个空格不该多烧一次建模。
        assertEquals(board, library.byBrief("  $brief\n"))
        assertEquals(board, library.find("board"))
        assertNull(library.byBrief("一块三格见方的木板"))
    }

    @Test
    fun aBlankRequirementIsNotACacheKey() {
        val library = PresetLibrary()
        library.learn(Preset(name = "x", brief = "", shape = boardShape))

        // 空要求会与**任何**别的空要求相等，当成缓存键就等于"所有没写要求的建模共用一份形状"。
        assertNull(library.byBrief(""))
        assertNull(library.byBrief("   "))
    }

    @Test
    fun theSameNameReplacesInsteadOfStacking() {
        val library = PresetLibrary()
        library.learn(board)
        val thinner = board.copy(shape = VoxelShape(solid = listOf(Voxel(0, 0, 0))))

        library.learn(thinner)

        assertEquals(1, library.generatedPresets().size)
        assertEquals(thinner, library.find("board"))
    }

    @Test
    fun builtinAndGeneratedStayInSeparatePacks() {
        val builtin = Preset(name = "board", brief = brief, shape = boardShape, origin = Preset.Origin.BUILTIN)
        val library = PresetLibrary(listOf(builtin))
        library.learn(board.copy(shape = VoxelShape(solid = listOf(Voxel(0, 0, 0)))))

        assertEquals(listOf("board"), library.builtinPresets().map { it.name })
        assertEquals(listOf("board"), library.generatedPresets().map { it.name })
        // 出厂那份**没有被生成的盖掉**：两个包分开，正是为了还能说清哪些是我们自己带的。
        assertEquals(boardShape, library.find("board")?.shape)
    }

    @Test
    fun restoringPutsBackExactlyWhatWasSaved() {
        val library = PresetLibrary()
        library.learn(board)
        val saved = library.generatedPresets()

        val reloaded = PresetLibrary()
        reloaded.restore(saved)

        assertEquals(saved, reloaded.generatedPresets())
        assertEquals(4, reloaded.byBrief(brief)?.shape?.cellCount)
    }

    @Test
    fun theFactoryHandsOverACopySoBreakingOneCupDoesNotBreakThemAll() {
        val library = PresetLibrary()
        library.learn(board)

        val first = library.spawn(board, "board_a", "家具", "木板", 100, 200, 0)
        val second = library.spawn(board, "board_b", "家具", "木板", 100, 400, 0)
        assertEquals(boardShape, first.shape)
        assertEquals(boardShape, second.shape)

        // 劈掉一块：**换掉它自己那一份**。预设与另一块都不动。
        val half = VoxelShape(solid = listOf(Voxel(0, 0, 0), Voxel(0, 5, 0)))
        val broken = first.copy(shape = half)

        assertEquals(half, broken.shape)
        assertEquals(boardShape, first.shape)
        assertEquals(boardShape, second.shape)
        assertEquals(boardShape, library.find("board")?.shape)
    }

    @Test
    fun theSpawnedThingKeepsItsOwnBackground() {
        val library = PresetLibrary()
        val cup = library.spawn(
            board, "cup", "器具", "酒杯", 0, 0, 0,
            background = "酒馆里的东西，木色发暗、釉面有磕碰",
        )

        // 用户定的：背景**不沿任何链继承**，每样东西自己一份，由生成它的那一步写进去。
        assertTrue(cup.background!!.startsWith("酒馆里的东西"))
        assertNull(library.spawn(board, "cup2", "器具", "酒杯", 0, 0, 0).background)
    }

    // ── 第二道关：关键词（A.14、§14.31）─────────────────────────────────────────────
    //
    // 实测里最贵的一课：同一棵橡树被造了 11 遍，因为模型每次写的要求都略微不同，而 `byBrief` 是逐字
    // 比对。下面这些钉的是**第二道关**——问的不是"是不是同一句话"，而是"是不是同一类东西"。

    private val oak = Preset(
        name = "oak_tree",
        brief = "一棵被雷劈过的歪脖子橡树，树皮皲裂",
        shape = boardShape,
        category = "树木",
        tags = listOf("橡树", "乔木", "落叶"),
    )

    private val pine = Preset(
        name = "pine_tree",
        brief = "一棵笔直的松树",
        shape = boardShape,
        category = "树木",
        tags = listOf("松树", "针叶"),
    )

    private val table = Preset(
        name = "oak_table",
        brief = "一张橡木方桌",
        shape = boardShape,
        category = "家具",
        tags = listOf("桌子", "木制"),
    )

    private fun library(): PresetLibrary = PresetLibrary(listOf(oak, pine, table))

    @Test
    fun aCategoryFindsEveryThingOfThatKind() {
        val found = library().search("树木")

        // 类别是粗筛：问"我要一棵树"，两棵都该回来——而它们的要求逐字不同，byBrief 一条都够不着。
        assertEquals(listOf("oak_tree", "pine_tree"), found.map { it.name }.sorted())
        assertNull(library().byBrief("一棵被雷劈过的歪脖子橡树"))
    }

    @Test
    fun aTagFindsOneThingByAWordThatIsNotInItsRequirement() {
        // 「乔木」一个字都没出现在那句要求里，而它正是人会说出来的词——这就是属性存在的理由。
        val found = library().search("乔木")

        assertEquals(listOf("oak_tree"), found.map { it.name })
    }

    @Test
    fun whatsInTheAttributesOutranksWhatsOnlyInTheRequirement() {
        // 同一个词「木制」：一条挂在**标签**上，一条只出现在**那句要求**里。两档是故意分开的——
        // 属性是专门为搜索写的，而要求是为了画图写的，里面全是修饰词。
        val tagged = oak.copy(name = "tagged", brief = "一句跟那个词没关系的要求", tags = listOf("木制"))
        val onlyDescribed = table.copy(name = "described", brief = "一张木制的桌子", tags = emptyList())
        val both = PresetLibrary(listOf(onlyDescribed, tagged))

        val found = both.search("木制")

        assertEquals(listOf("tagged", "described"), found.map { it.name })
    }

    @Test
    fun anEmptyQueryFindsNothingRatherThanEverything() {
        // 空查询返回全部，会让"搜不到"与"懒得搜"长得一模一样——而这两种情况该给模型的话完全不同。
        assertTrue(library().search("").isEmpty())
        assertTrue(library().search("   ").isEmpty())
    }

    @Test
    fun nothingMatchesIsAnEmptyListNotAnError() {
        assertTrue(library().search("潜水艇").isEmpty())
    }

    @Test
    fun aSearchLooksInBothPacksAndStopsAtTheLimit() {
        val library = library()
        library.learn(oak.copy(name = "grown_oak", brief = "长出来的一棵橡树"))

        assertEquals(2, library.search("橡树").count { it.name.contains("oak") })

        // 上限是给模型看的，不是性能上限：候选列多了它挑不过来。
        val many = PresetLibrary((1..20).map { oak.copy(name = "oak_$it") })
        assertEquals(PresetLibrary.MAX_RESULTS, many.search("橡树").size)
    }
}
