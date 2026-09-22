package io.github.k1midi0.infinitesimal.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 账户余额。
 *
 * 它是**账户**的状态，不是这次会话的状态：一次请求花掉多少、还剩多少，端点自己算。应用只需要
 * 知道「还能不能继续」，以及还剩多少——那是唯一一件在余额见底之前能让人提前反应的事。
 *
 * 金额一律留作字符串。端点发来的就是字符串（`"8.27"`），而把它转成浮点再转回去，会在一个只用来
 * 显示和比较的数上引入没有必要的表示误差。要比较大小的地方自己解析，解析失败是显式的。
 */
data class Balance(
    /** 账户此刻是不是还能用。端点自己说，不由本地阀值猜。 */
    val isAvailable: Boolean,
    val amounts: List<BalanceAmount>,
) {
    /** 主币种那一份。多数账户只有一份，没有时返回空。 */
    val primary: BalanceAmount? get() = amounts.firstOrNull()

    /**
     * 表上那一行要显示的样子。读不出主币种时返回 null。
     *
     * 只写数字与币种，不加「余额」「还剩」这类词——那一行的标签由表来给，这一份只管值。
     *
     * **它是钱，不是 token。** 端点给的是账户余额，换算成 token 需要知道单价，而单价随模型与
     * 缓存命中率变。写一个算不出准确值的 token 数就是编数据。
     */
    val display: String? get() = primary?.let { "${it.total} ${it.currency}" }
}

/**
 * 一个币种的余额。
 *
 * [granted] 是赠送的、[toppedUp] 是充值的，两者相加是 [total]。分开留着是因为它们花完的方式不
 * 一样：赠送的先花，而「还剩多少」这个问题在两者都记着的时候才答得准。
 */
data class BalanceAmount(
    val currency: String,
    val total: String,
    val granted: String,
    val toppedUp: String,
)

/**
 * 端点返回的形状。
 *
 * 与 [Balance] 分开：那是应用要的东西，这是线上传的东西。两者重合的时候短，而一旦分开，端点
 * 加一个字段不会渗进界面，界面想换个摆法也不用改序列化。
 */
@Serializable
internal data class BalanceResponse(
    @SerialName("is_available") val isAvailable: Boolean = false,
    @SerialName("balance_infos") val infos: List<BalanceInfo> = emptyList(),
)

@Serializable
internal data class BalanceInfo(
    val currency: String = "",
    @SerialName("total_balance") val total: String = "",
    @SerialName("granted_balance") val granted: String = "",
    @SerialName("topped_up_balance") val toppedUp: String = "",
)

internal fun BalanceResponse.toBalance(): Balance = Balance(
    isAvailable = isAvailable,
    amounts = infos.map {
        BalanceAmount(
            currency = it.currency,
            total = it.total,
            granted = it.granted,
            toppedUp = it.toppedUp,
        )
    },
)
