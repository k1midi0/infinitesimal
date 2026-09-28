package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * **人设追加那一层**（§14.52）。
 *
 * 这一支钉的是"这一次改了什么"，而不是"这句话写得好不好"：
 *
 *  - **按标题改**：标题对上的替换那一张（**位置不动**），对不上的追加到末尾——两件事都要成立，
 *    因为"这一趟添了一条"与"这一趟改变了它原来的某一面"是两种真实的经历。
 *  - **六道门**：空 id、世界里没有、不是角色、本来一张卡都没有、看不见、同一批里写了两次。
 *  - **两道上限**：一张卡 [CardPlanner.MAX_CARD_CHARS]、一个人合计 [CardPlanner.CARD_BUDGET_CHARS]
 *    —— 到顶之后**驳回**，并把现有的标题列回去（出路是改写，不是丢弃）。
 *  - 卡那一段的解析：**标题不能省**。
 */
class CardPlannerTest {

    private fun world() = World.from(StartingWorld.snapshot())

    /** 矮人（杜林）的卡：身份 / 性格 / 交互范围 / 口癖。 */
    private val dwarfCards = listOf("身份", "性格", "交互范围", "口癖")

    private fun write(title: String, content: String, id: String = "dwarf") =
        CardWrite(id, listOf(CharacterCard(title, content)))

    // ── 合并语义 ────────────────────────────────────────────────────────────────────

    @Test
    fun aTitleThatIsNotThereYetIsAppendedAtTheEnd() {
        val w = world()

        val problems = CardPlanner.problems(w, listOf(write("经历", "你替那个过路人挡了一刀。")), setOf("dwarf"))
        assertTrue(problems.isEmpty(), "该写得进去：$problems")
        CardPlanner.apply(w, listOf(write("经历", "你替那个过路人挡了一刀。")))

        val cards = w.byId("dwarf")!!.cards
        assertEquals(dwarfCards + "经历", cards.map { it.title }, "新的一条添在末尾")
        assertEquals("你替那个过路人挡了一刀。", cards.last().content)
    }

    @Test
    fun aTitleThatIsAlreadyThereReplacesThatCardAndKeepsItsPlace() {
        val w = world()
        // 「性格」是第二张。改写它之后**位置不该动**：一个角色的卡是有编辑次序的（身份在头一张最自然）。
        CardPlanner.apply(w, listOf(write("性格", "你现在话多了一点——有人替你挨了一刀之后。")))

        val cards = w.byId("dwarf")!!.cards
        assertEquals(dwarfCards, cards.map { it.title }, "标题序列一个字没变")
        assertEquals("你现在话多了一点——有人替你挨了一刀之后。", cards[1].content, "第二张被换掉了")
        assertEquals(4, cards.size, "没有多出一张")
    }

    @Test
    fun severalCardsInOneWrite() {
        val w = world()
        val both = CardWrite(
            "dwarf",
            listOf(CharacterCard("经历", "这一趟你去了地窖。"), CharacterCard("口癖", "你现在说到酒就咂嘴。")),
        )
        assertTrue(CardPlanner.problems(w, listOf(both), setOf("dwarf")).isEmpty())
        CardPlanner.apply(w, listOf(both))

        val cards = w.byId("dwarf")!!.cards
        assertEquals(5, cards.size, "一张添了、一张改了")
        assertEquals("这一趟你去了地窖。", cards.last().content)
        assertEquals("你现在说到酒就咂嘴。", cards.first { it.title == "口癖" }.content)
    }

    // ── 六道门 ──────────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyIdIsRefused() {
        val problems = CardPlanner.problems(world(), listOf(write("经历", "……", id = "")), setOf("dwarf"))
        assertTrue(problems.single().contains("没说要写给谁"), problems.single())
    }

    @Test
    fun somebodyWhoIsNotInTheWorldIsRefused() {
        val problems = CardPlanner.problems(world(), listOf(write("经历", "……", id = "nobody")), setOf("nobody"))
        assertTrue(problems.single().contains("世界里没有"), problems.single())
    }

    @Test
    fun somethingThatIsNotACharacterIsRefused() {
        // 酒杯是物品（kind 不是「角色」）。
        val problems = CardPlanner.problems(world(), listOf(write("经历", "……", id = "wine_cup")), setOf("wine_cup"))
        assertTrue(problems.single().contains("不是角色"), problems.single())
    }

    @Test
    fun somethingWithNoCardsIsRefusedAndPointedAtTheRightHand() {
        val w = world()
        // 桌上那盏油灯：它在世界里、`kind` 也不是角色——所以为了只考"没有卡"这一道门，造一个
        // **kind 是角色、而一张卡都没有**的东西（那种状态只有代码写得出来，那正是这条门要拦的）。
        w.put(Entity(id = "stranger", kind = CHARACTER_KIND, label = "没来历的人", x = 1200, y = 300, z = 12800))

        val problems = CardPlanner.problems(w, listOf(write("经历", "……", id = "stranger")), setOf("stranger"))
        assertTrue(problems.single().contains("一张卡都没有"), problems.single())
        assertTrue(
            "spawn_character" in problems.single(),
            "驳回理由要指到那条正当的路上去：${problems.single()}",
        )
    }

    @Test
    fun somebodyYouCannotSeeIsRefused() {
        val problems = CardPlanner.problems(world(), listOf(write("经历", "……")), visible = emptySet())
        assertTrue(problems.single().contains("不在你这一轮看得见的范围里"), problems.single())
    }

    @Test
    fun theSamePersonTwiceInOneBatchIsRefused() {
        val problems = CardPlanner.problems(
            world(),
            listOf(write("经历", "甲"), write("口癖", "乙")),
            setOf("dwarf"),
        )
        assertTrue(problems.single().contains("写了两次"), problems.single())
    }

    // ── 两道上限 ────────────────────────────────────────────────────────────────────

    @Test
    fun aCardThatIsTooLongIsRefused() {
        val problems = CardPlanner.problems(
            world(),
            listOf(write("经历", "字".repeat(401))),
            setOf("dwarf"),
        )
        assertTrue(problems.single().contains("太长"), problems.single())
        assertTrue("401" in problems.single() && "400" in problems.single(), "要报出两个数：${problems.single()}")
    }

    @Test
    fun goingOverTheBudgetIsRefusedAndListsWhatItAlreadyHas() {
        val w = world()
        val problems = CardPlanner.problems(
            w,
            listOf(write("经历", "字".repeat(400))),
            setOf("dwarf"),
            budgetChars = 500,
        )
        val why = problems.single()
        assertTrue("上限是 500" in why, why)
        // **出路是改写**——所以理由里要把现有的标题列出来（"写出它们的标题就等于替换它们"）。
        assertTrue("身份" in why && "口癖" in why, "要列出现有的卡：$why")
        assertTrue("改写" in why, why)
    }

    @Test
    fun rewritingAnExistingCardCanFitInsideTheBudget() {
        val w = world()
        // 预算很紧，但**改写**（不是新增）应当能过：把「性格」压短。
        val problems = CardPlanner.problems(
            w,
            listOf(write("性格", "话不多。")),
            setOf("dwarf"),
            budgetChars = 120,
        )
        assertTrue(problems.isEmpty(), "改写不该因为总量被驳回：$problems")
    }

    // ── 卡那一段怎么读 ──────────────────────────────────────────────────────────────

    @Test
    fun aLineWithoutATitleIsRefused() {
        val parsed = CardText.parse("你替那个过路人挡了一刀。")
        assertTrue(parsed is CardParse.Failed, "省掉标题要驳回：$parsed")
        assertTrue("标题" in (parsed as CardParse.Failed).why)
    }

    @Test
    fun titlesAndContentsAreParsed() {
        val parsed = CardText.parse(
            """
            经历｜你替那个过路人挡了一刀，疤留在左臂上。
            口癖｜你现在说到钱会哼一声。
            """.trimIndent(),
        )
        val cards = (parsed as CardParse.Parsed).cards
        assertEquals(listOf("经历", "口癖"), cards.map { it.title })
        assertEquals("你替那个过路人挡了一刀，疤留在左臂上。", cards.first().content)
    }

    @Test
    fun aTitleWithNoContentIsRefused() {
        assertTrue(CardText.parse("经历｜") is CardParse.Failed)
        assertTrue(CardText.parse("经历｜　") is CardParse.Failed)
    }

    @Test
    fun nothingToWriteIsRefused() {
        assertTrue(CardText.parse("   \n  ") is CardParse.Failed)
    }

    @Test
    fun renderGoesBackToOneLine() {
        assertEquals("经历｜甲乙", CardText.render(CharacterCard("经历", "甲乙")))
    }

    /** 提示词与答复共用的那一行（标题 + 字数）——两处不会各说各话的原因就在它只有一份。 */
    @Test
    fun titlesWithSizeNamesEveryCard() {
        val line = world().byId("dwarf")!!.cards.titlesWithSize()
        assertTrue("身份" in line && "口癖" in line, line)
        assertTrue("字" in line, line)
        assertEquals("（一张卡都没有）", emptyList<CharacterCard>().titlesWithSize())
    }

    @Test
    fun applyDoesNotTouchAnybodyElse() {
        val w = world()
        val playerBefore = w.byId("player")!!.cards
        CardPlanner.apply(w, listOf(write("经历", "……")))
        assertEquals(playerBefore, w.byId("player")!!.cards, "只动那一个")
        assertNull(w.byId("nobody"), "不该凭空多出一个人")
    }
}
