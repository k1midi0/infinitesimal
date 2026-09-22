package io.github.k1midi0.infinitesimal.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether the model is asked to deliberate before it answers.
 *
 * A closed set rather than a free string, and that is the whole reason it exists. The endpoint
 * ignores a thinking type it does not recognise, so a typo in the configuration file would
 * not fail — it would produce a different amount of thinking, silently, and the only evidence
 * would be the bill and the latency. Here a value outside these two makes the file
 * malformed, which the application reports and refuses to start on.
 *
 * The names are spelled out because they are what the request will literally contain, and the
 * file is meant to say what the request says.
 *
 * 这一版只有两个取值：`enabled` 与 `disabled`。端点还收 `adaptive`（由它自己决定要不要
 * 思考），但它与 `enabled` 在真机上跑出来的差别落在抖动里——摆出来只会让人以为自己调细了。
 */
@Serializable
enum class ThinkingMode(val wireName: String) {

    /** 每次都思考。 */
    @SerialName("enabled")
    Always("enabled"),

    /** 从不思考。请求里因此也不会出现 `reasoning_effort`（规则见 `ThinkingRequest.of`）。 */
    @SerialName("disabled")
    Never("disabled"),
}
