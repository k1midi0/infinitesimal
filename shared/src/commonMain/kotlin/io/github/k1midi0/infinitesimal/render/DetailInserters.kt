package io.github.k1midi0.infinitesimal.render

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.llm.FimClient
import io.github.k1midi0.infinitesimal.llm.LlmClient

/**
 * 细化路径的装配。**默认只挂工具路，FIM 要显式打开。**
 *
 * 这不是保守，是实测：**FIM 五次里只有两次可用**（§14.3），而它的失败方式都是"看起来正常"的那种
 * ——空返回、假标记 `<cont=12>`、以及把端点的**安全审核标记**当作补全结果返回，而 `finish_reason`
 * 仍然是 `stop`（§14.4）。
 *
 * 于是默认值应当选**稳的那一条**。FIM 留着，因为它有一样东西工具给不了：`suffix` 是**协议级**的锚，
 * 模型没有机会碰到两侧的文字（§14.6）。但那是一个值得**主动开启**的收益，不该是一个默认承担的风险。
 *
 * 两条路都在 [SystemId.RENDERER] 那个缓存池里——细化是渲染的一部分，不是另一个系统。
 */
object DetailInserters {

    fun of(config: EndpointConfig, useFim: Boolean = false): DetailInserter {
        val rendererConfig = config.copy(userId = SystemId.RENDERER)
        return DetailInserter(
            primary = if (useFim) FimSentenceWriter(FimClient(rendererConfig)) else null,
            fallback = ToolSentenceWriter(LlmClient(rendererConfig)),
        )
    }
}
