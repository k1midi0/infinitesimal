package io.github.k1midi0.infinitesimal.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 思考用多大劲。
 *
 * 在 OpenAI 格式下它叫 `reasoning_effort`，与思考开关是两个独立的参数：开关决定想不想，它决定
 * 想多久。
 *
 * **只有三个取值，尽管端点收得下七个。** 它有一张映射表：
 *
 * 	请求传入        实际映射
 * 	minimal    ->   low
 * 	low        ->   low
 * 	medium     ->   high
 * 	high       ->   high
 * 	xhigh      ->   high
 * 	max        ->   max
 * 	ultra      ->   max
 *
 * 七个名字只有三种效果。把七个都摆出来，等于给三个档位配七个名字——而**换一个名字不换效果**
 * 正是这个项目一直在禁的那种开关：使用者以为自己调细了，实际什么也没变，而唯一能发现的办法是
 * 去读这张表。
 *
 * 与 [ThinkingMode] 同样用封闭枚举。表外的取值会落到哪里没有验证过，而落到哪里都不会报错——
 * 唯一的证据是账单与延迟。所以范围外的取值应当让文件变成 malformed，由应用报出来。
 *
 * 名字写成线上真正会发的字符串，所以配置文件里写的就是请求里发的。
 */
@Serializable
enum class ReasoningEffort(val wireName: String) {

    /** 少想。映射表里的 minimal 与 low 都落在这里。 */
    @SerialName("low")
    LOW("low"),

    /** 默认。映射表里的 medium、high、xhigh 都落在这里。 */
    @SerialName("high")
    HIGH("high"),

    /** 顶格。映射表里的 max 与 ultra 都落在这里。 */
    @SerialName("max")
    MAX("max"),
}
