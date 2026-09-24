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
}
