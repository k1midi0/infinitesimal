package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.test.assertTrue

/**
 * 形状进出的那一种表示法（附录 A.11）。
 *
 * 这是**整条建模链路上唯一一处能被穷尽测的地方**：模型画的东西好不好看没法断言，但它画出来的
 * 那张图能不能读成格子、读出来的东西合不合理，全在这里。所以这里既有"读得对"，也有"读错时报在
 * 哪一行"——后一半同样是产品行为，因为那些字符串会原样回给模型（§1.3）。
 */
class ShapeGridTest {

    private fun ok(text: String): VoxelShape {
        val parsed = ShapeGrid.parse(text)
        // 注意用 `fail` 而不是 `assertIs` 的 message 参数：那个 message 是**立刻求值**的，于是
        // "本该成功"的那条分支会先自己去 cast 一遍 Bad，把一条断言失败变成 ClassCastException。
        if (parsed is ShapeGrid.Parsed.Bad) fail("本该读成功，实际：${parsed.problems}")
        return (parsed as ShapeGrid.Parsed.Ok).shape
    }

    private fun bad(text: String): List<String> {
        val parsed = ShapeGrid.parse(text)
        if (parsed is ShapeGrid.Parsed.Ok) fail("本该读失败，实际读成了 ${parsed.shape}")
        return (parsed as ShapeGrid.Parsed.Bad).problems
    }

    /** 一块 15×10×5 厘米的板：3×2×1 个格。 */
    private val plank = VoxelShape(
        solid = listOf(
            Voxel(0, 0, 0), Voxel(5, 0, 0), Voxel(10, 0, 0),
            Voxel(0, 5, 0), Voxel(5, 5, 0), Voxel(10, 5, 0),
        ),
    )

    @Test
    fun aShapeSurvivesARoundTrip() {
        val text = ShapeGrid.render(plank)
        assertEquals(
            """
            z=0
            ###
            ###
            """.trimIndent() + "\n",
            text,
        )
        val back = ok(text)
        assertEquals(plank.solid.toSet(), back.solid.toSet())
        assertTrue(back.pointMass.isEmpty())
    }

    @Test
    fun emptyLayersAreSkippedButTheHeightSurvives() {
        // z=0 是底、z=10 是顶，中间那层空着。**层号靠 z= 头记住，不靠顺序**——所以它能省掉。
        val parsed = ok(
            """
            z=0
            ##
            z=10
            ##
            """.trimIndent(),
        )
        assertEquals(setOf(Voxel(0, 0, 0), Voxel(5, 0, 0), Voxel(0, 0, 10), Voxel(5, 0, 10)), parsed.solid.toSet())
        assertEquals(Voxel(5, 0, 10), parsed.extent)
    }

    @Test
    fun pointsAreCountedPerCell() {
        val parsed = ok(
            """
            z=0
            1+9
            """.trimIndent(),
        )
        assertEquals(
            mapOf(Voxel(0, 0, 0) to 1, Voxel(5, 0, 0) to 10, Voxel(10, 0, 0) to 9),
            parsed.pointMass.associate { it.voxel to it.count },
        )
    }

    @Test
    fun aRowThatIsTheWrongLengthIsCaughtByLine() {
        val problems = bad(
            """
            z=0
            ###
            ##
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("第 3 行") && it.contains("一样长") }, problems.toString())
    }

    @Test
    fun trailingDotsAreNotTrimmedAway() {
        // 行尾的点是实义字符：裁掉它，"少画了几个字符"就没有症状了。
        val problems = bad(
            """
            z=0
            ###.
            ###
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("一样长") }, problems.toString())
    }

    @Test
    fun anUnknownCharacterIsReportedWithItsPlace() {
        val problems = bad(
            """
            z=0
            #X#
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("第 2 行第 2 个字符") && it.contains("X") }, problems.toString())
    }

    @Test
    fun cellsWithoutALayerHeaderAreRefused() {
        val problems = bad("###")
        assertTrue(problems.any { it.contains("没有 z=") }, problems.toString())
    }

    @Test
    fun aRepeatedLayerIsRefused() {
        val problems = bad(
            """
            z=0
            #
            z=0
            #
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("出现了两次") }, problems.toString())
    }

    @Test
    fun solidAndPointsCannotShareACell() {
        // **网格里做不出这种形状**（一个格一个字符），所以只能直接构造。它在类型层面被一条校验守着，
        // 而不是靠"画不出来"——因为形状还有别的来源（缓存、存档、以后的手工编辑）。
        val problems = VoxelShape(
            solid = listOf(Voxel(0, 0, 0)),
            pointMass = listOf(PointCluster(Voxel(0, 0, 0), 1)),
        ).problems()
        assertTrue(problems.any { it.contains("互斥") }, problems.toString())
    }

    @Test
    fun theFloorAndTheCeilingOfTheGridAreChecked() {
        // 空形状
        assertTrue(bad("z=0\n.\n").any { it.contains("空") })

        // 不是 5 厘米的整数倍
        assertTrue(VoxelShape(solid = listOf(Voxel(3, 0, 0))).problems().any { it.contains("整数倍") })

        // 负坐标
        assertTrue(VoxelShape(solid = listOf(Voxel(-5, 0, 0))).problems().any { it.contains("负") })

        // 质点超过十个
        assertTrue(
            VoxelShape(pointMass = listOf(PointCluster(Voxel(0, 0, 0), 11))).problems().any { it.contains("1..10") },
        )

        // 同一个格出现两次
        assertTrue(
            VoxelShape(solid = listOf(Voxel(0, 0, 0), Voxel(0, 0, 0))).problems().any { it.contains("两次") },
        )

        // 单边超限
        assertTrue(
            VoxelShape(solid = listOf(Voxel(0, 0, 0), Voxel(300, 0, 0)))
                .problems()
                .any { it.contains("单边上限") },
        )

        // 最小角不在原点
        assertTrue(
            VoxelShape(solid = listOf(Voxel(5, 5, 5))).problems().any { it.contains("最小角必须在") },
        )
    }

    @Test
    fun oneProblemDoesNotHideTheOthers() {
        // **一次报全**，而不是报第一条：模型修错的能力与它拿到的信息成正比，只报一条就得来回几轮，
        // 而每一轮都是一次完整的调用。
        val problems = VoxelShape(
            solid = listOf(Voxel(5, 5, 5), Voxel(0, 0, 0), Voxel(0, 0, 0)),
            pointMass = listOf(PointCluster(Voxel(0, 0, 0), 99)),
        ).problems()
        // 三条：同一个格出现两次、质点超十个、同一个格既实心又质点。
        assertTrue(problems.size >= 3, problems.toString())
    }

    @Test
    fun aBiggerHeightIsAllowedWhenTheCallerSaysSo() {
        // 单边上限是**参数**：物品两米封顶是合理的，一个地形块显然不是（附录 A.10）。
        val wide = VoxelShape(solid = listOf(Voxel(0, 0, 0), Voxel(1000, 0, 0)))
        assertTrue(wide.problems().any { it.contains("单边上限") })
        assertTrue(wide.problems(ShapeLimits(maxSideCm = 2000)).isEmpty())
    }
}
