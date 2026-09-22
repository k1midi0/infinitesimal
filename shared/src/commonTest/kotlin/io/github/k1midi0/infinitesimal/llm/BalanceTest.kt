package io.github.k1midi0.infinitesimal.llm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 余额那一页的解析。
 *
 * 这一条不联网：真实的那一次调用是手工量过的（HTTP 200，形状如下），而这里钉住的是**形状变了
 * 会被发现**——端点加一个字段、把金额从字符串换成数字、或者把那一层数组去掉，都会在这里响。
 */
class BalanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): Balance =
        json.decodeFromString(BalanceResponse.serializer(), text).toBalance()

    @Test
    fun readsTheShapeTheEndpointActuallySends() {
        val balance = parse(
            """{"is_available":true,"balance_infos":[{"currency":"CNY",""" +
                """"total_balance":"8.27","granted_balance":"0.00",""" +
                """"topped_up_balance":"8.27"}]}""",
        )

        assertTrue(balance.isAvailable)
        val primary = balance.primary
        assertEquals("CNY", primary?.currency)
        assertEquals("8.27", primary?.total)
        assertEquals("0.00", primary?.granted)
        assertEquals("8.27", primary?.toppedUp)
    }

    /**
     * 金额留作字符串，不做转换。
     *
     * 端点发来的就是字符串，而把它转成浮点再转回去，会在一个只用来显示和比较的数上引入没有
     * 必要的表示误差。这一条钉住的是「别顺手改成 Double」。
     */
    @Test
    fun keepsAmountsAsWritten() {
        val balance = parse(
            """{"is_available":true,"balance_infos":[{"currency":"CNY",""" +
                """"total_balance":"110.00","granted_balance":"10.00",""" +
                """"topped_up_balance":"100.00"}]}""",
        )

        assertEquals("110.00", balance.primary?.total, "末尾那两个零是端点写的，不该被吃掉")
    }

    /**
     * 不可用是一个正常结果，不是解析失败。
     */
    @Test
    fun anUnavailableAccountStillParses() {
        val balance = parse("""{"is_available":false,"balance_infos":[]}""")

        assertFalse(balance.isAvailable)
        assertEquals(null, balance.primary)
    }

    /**
     * 多币种账户把每一份都留着，顺序照端点给的。
     */
    @Test
    fun keepsEveryCurrency() {
        val balance = parse(
            """{"is_available":true,"balance_infos":[""" +
                """{"currency":"CNY","total_balance":"1.00","granted_balance":"0.00","topped_up_balance":"1.00"},""" +
                """{"currency":"USD","total_balance":"2.00","granted_balance":"0.00","topped_up_balance":"2.00"}]}""",
        )

        assertEquals(listOf("CNY", "USD"), balance.amounts.map { it.currency })
        assertEquals("CNY", balance.primary?.currency, "第一份是主币种")
    }
}