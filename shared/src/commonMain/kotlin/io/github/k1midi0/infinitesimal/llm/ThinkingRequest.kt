package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode

/**
 * 一次请求里「要不要思考」与「思考用多大劲」这两件事。
 *
 * 放在一起，因为它们是一起决定的：思考关着的时候 effort 没有意义。分开传就会有一个调用点忘记
 * 这件事，而忘掉它的症状是请求里挂着一个已经被关掉的能力的强度——不报错，只是这条请求说的不是
 * 它想说的意思，而它还会出现在任务日志里。
 */
data class ThinkingRequest(
    val options: ThinkingOptions,
    val effort: String?,
) {
    companion object {

        /**
         * 从配置里的那两项造出来。
         *
         * 这一条规则只写在这里。它有两个可能的调用点（主循环与判定器），而判定器根本不用它——
         * 它自己把思考关掉。写两遍的话，漂移的方式是其中一处会在思考关着的时候仍然把 effort
         * 发出去。
         */
        fun of(mode: ThinkingMode, effort: ReasoningEffort): ThinkingRequest = ThinkingRequest(
            options = ThinkingOptions(mode.wireName),
            effort = if (mode == ThinkingMode.Never) null else effort.wireName,
        )
    }
}
