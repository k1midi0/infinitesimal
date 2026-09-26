package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **放一个人那道门**（§14.46）：校验什么、不校验什么。
 *
 * 与 [CyclePlannerTest] 同一个位置——它不花钱、不过模型，所以能一条一条地钉住；而导演那只手只负责
 * 把这几条**在落库之前**读出来。
 *
 * 判据刻意分成两栏：
 *
 *  - **它能不能放**（id 空/重名、没名字、没卡、位置看不见、作息立不住）——代码判得出来的那些；
 *  - **这个人合不合常理**——一条都不在这里。代码去判它就得读懂那句话，于是会开始静默地判错
 *    （A.6、A.7）。
 */
class CharacterPlannerTest {

    private fun world(): World = World.from(StartingWorld.snapshot())

    /** 玩家开局站在矮人旁边（(1000, 200, 12800)），所以那一片里的人与家具都看得见。 */
    private fun visible(world: World): Set<String> = visibleTo(world, "player")

    private fun cards(vararg content: String) = content.map { CharacterCard("身份", it) }

    /** 一份合格的提议：一个站在矮人旁边、有卡、没有作息的剧情角色。 */
    private fun smith(
        id: String = "smith",
        label: String = "铁匠格里姆",
        at: CharacterPoint = CharacterPoint(1000, 200, 12800),
        beats: List<Beat> = emptyList(),
        cards: List<CharacterCard> = cards("你是铁匠格里姆。"),
    ) = CharacterWrite(id = id, label = label, cards = cards, at = at, beats = beats)

    private fun problems(world: World, vararg writes: CharacterWrite): List<String> =
        CharacterPlanner.problems(world, writes.toList(), visible(world))

    @Test
    fun aStoryCharacterWithNoCyclePasses() {
        val world = world()
        assertTrue(problems(world, smith()).isEmpty(), "实得 ${problems(world, smith())}")
    }

    @Test
    fun anAmbientCharacterWithACyclePasses() {
        val world = world()
        val write = smith(
            beats = listOf(
                Beat(0, "在铺子里拉风箱。", 1000, 200, 12800),
                Beat(300, "去地窖拿炭。", 1300, 100, 12800),
            ),
        )
        assertTrue(problems(world, write).isEmpty(), "实得 ${problems(world, write)}")
    }

    @Test
    fun anEmptyIdIsRefused() {
        val world = world()
        assertTrue(problems(world, smith(id = "  ")).any { "没起 id" in it })
    }

    @Test
    fun anIdThatIsAlreadyTakenIsRefused() {
        val world = world()
        val found = problems(world, smith(id = "dwarf", label = "另一个杜林"))
        assertTrue(found.any { "已经有 id" in it }, "实得 $found")
        assertTrue(found.any { "write_cycle" in it }, "理由要指出改日子走哪只手：$found")
    }

    @Test
    fun aCharacterWithNoCardsIsNotACharacter() {
        val world = world()
        val found = problems(world, smith(cards = emptyList()))
        assertTrue(found.any { "一张卡都没有" in it }, "实得 $found")
    }

    @Test
    fun aCharacterWithNoLabelIsRefused() {
        val world = world()
        assertTrue(problems(world, smith(label = " ")).any { "没有名字" in it })
    }

    @Test
    fun aPlaceNobodyCanSeeIsOnlyAGuess() {
        val world = world()
        val far = CharacterPoint(900_000, 900_000, 12800)
        val found = problems(world, smith(at = far))
        assertTrue(found.any { "看得见的范围" in it }, "实得 $found")
        // 位置本身过不去时就不该继续报作息的问题——那一栏全是它修不动的（"先让它进到眼前"才是它
        // 这一步唯一做得动的事）。
        assertTrue(world.byId("smith") == null)
    }

    @Test
    fun everyBeatAlsoHasToBeSomewhereItCanSee() {
        val world = world()
        val write = smith(
            beats = listOf(
                Beat(0, "在铺子里拉风箱。", 1000, 200, 12800),
                Beat(300, "去一个没看过的地方。", 900_000, 900_000, 12800),
            ),
        )
        assertTrue(problems(world, write).any { "第 300 分钟那一格" in it }, "实得 ${problems(world, write)}")
    }

    @Test
    fun theFirstBeatHasToBeWhereItStartsOut() {
        val world = world()
        val write = smith(
            at = CharacterPoint(1300, 100, 12800),
            beats = listOf(
                Beat(0, "在铺子里拉风箱。", 1000, 200, 12800),
                Beat(300, "去地窖拿炭。", 1300, 100, 12800),
            ),
        )
        assertTrue(problems(world, write).any { "走错的那一格" in it }, "实得 ${problems(world, write)}")
    }

    @Test
    fun aCycleThatCannotStandIsRefusedByTheSameRulesAsAnyOther() {
        val world = world()
        val write = smith(
            beats = listOf(Beat(0, "在铺子里拉风箱。", 1000, 200, 12800)),
        )
        assertTrue(problems(world, write).any { "至少两格" in it }, "实得 ${problems(world, write)}")
    }

    @Test
    fun theSameIdTwiceInOneBatchIsRefused() {
        val world = world()
        val found = problems(world, smith(), smith())
        assertTrue(found.any { "出现了不止一次" in it }, "实得 $found")
    }

    // ── 落库 ───────────────────────────────────────────────────────────────────────

    @Test
    fun applyingPutsARealCharacterInTheWorld() {
        val world = world()
        CharacterPlanner.apply(world, listOf(smith()))

        val placed = world.byId("smith")!!
        assertEquals(CHARACTER_KIND, placed.kind)
        assertEquals(1000, placed.x)
        assertEquals(200, placed.y)
        assertEquals(12800, placed.z)
        assertEquals(1, placed.cards.size)
        assertTrue(placed.cycle.isEmpty, "没写作息就是剧情角色")
        assertTrue(placed.notes.isEmpty() && placed.todos.isEmpty(), "它一出生是干净的")
    }

    /**
     * **`background` 是照抄它站的那一处，不是编的。**
     *
     * 它站的地方与"酒馆大厅"那一个实体够得着，而那一句本来就在世界里——新角色身上没有形状，渲染它的
     * 人只剩这一句可读。
     */
    @Test
    fun itsBackgroundIsCopiedFromWhateverIsNextToIt() {
        val world = world()
        CharacterPlanner.apply(world, listOf(smith()))

        val background = world.byId("smith")!!.background
        assertTrue(background != null, "旁边就有地方在，不该没有背景")
        assertTrue("酒馆" in background, "要抄那一处自己的话：实得 $background")
        assertTrue("铁匠格里姆" in background, "还要说得出它就在这里")
    }

    /** 旁边什么都没有时**明说没有**，而不是给一个空串——空串在提示词里读起来像"这里什么都没有"。 */
    @Test
    fun aFarAwayPlaceGetsNoBackgroundRatherThanAnEmptyOne() {
        val world = world()
        val alone = smith(id = "hermit", at = CharacterPoint(200_000, 200_000, 12800))
        // 那里看不见，所以先把它放进世界里（这一支验的是 apply 那一半）。
        CharacterPlanner.apply(world, listOf(alone))

        assertNull(world.byId("hermit")!!.background)
    }

    @Test
    fun applyingTwiceIsAProgrammingErrorNotASilentOverwrite() {
        val world = world()
        CharacterPlanner.apply(world, listOf(smith()))
        val second = runCatching { CharacterPlanner.apply(world, listOf(smith())) }
        assertTrue(
            second.exceptionOrNull() is IllegalArgumentException,
            "重名是不该发生的，实得 ${second.exceptionOrNull()}",
        )
    }

    // ── 读文本 ─────────────────────────────────────────────────────────────────────

    @Test
    fun aCardLineIsTitleThenContent() {
        val parsed = CharacterText.parseCards("身份｜你是铁匠。\n脾气｜你不爱说话。")
        assertEquals(listOf("身份", "脾气"), parsed.map { it.title })
        assertEquals("你不爱说话。", parsed[1].content)
    }

    /** 一行里没有分隔符时，它本来也没写标题——整行当正文收下，而不是丢掉那一行。 */
    @Test
    fun aCardLineWithoutATitleIsStillACard() {
        val parsed = CharacterText.parseCards("你是铁匠格里姆。")
        assertEquals(1, parsed.size)
        assertEquals("身份", parsed.first().title)
        assertEquals("你是铁匠格里姆。", parsed.first().content)
    }

    @Test
    fun blankCardLinesAreSkipped() {
        assertEquals(1, CharacterText.parseCards("\n身份｜你是铁匠。\n  \n").size)
        assertTrue(CharacterText.parseCards("   ").isEmpty())
    }

    @Test
    fun aPointIsReadInEveryShapeTheModelWritesIt() {
        assertEquals(Triple(1000, 200, 12800), CharacterText.parsePoint("1000,200,12800"))
        assertEquals(Triple(1000, 200, 12800), CharacterText.parsePoint("(1000，200，12800)"))
        assertEquals(Triple(1, 2, 3), CharacterText.parsePoint(" 1, 2, 3 "))
        assertNull(CharacterText.parsePoint("1000,200"))
        assertNull(CharacterText.parsePoint("一千,二百,三万"))
    }

    /**
     * 只报看得见的位置，而"看得见"的判据是**离一个看得见的实体够近**——不是坐标逐字相等。
     *
     * 清单里印的是每样东西**自己的**位置，而"站在柜台后面"与"柜台"本来就是两处坐标；要求逐字相等会
     * 把每一个正常的位置都判成编的。
     *
     * 这里挑的一处是**矮人旁边半米**：它在它那一格里，所以看得见；而半条街开外（那一片的射线根本
     * 够不到）就没依据了。
     */
    @Test
    fun standingNextToAVisibleThingCountsAsBeingSomewhereItCanSee() {
        val world = world()
        assertTrue(problems(world, smith(at = CharacterPoint(950, 200, 12800))).isEmpty())
        assertTrue(
            problems(world, smith(at = CharacterPoint(5000, 5000, 12800))).any { "看得见的范围" in it },
        )
    }
}
