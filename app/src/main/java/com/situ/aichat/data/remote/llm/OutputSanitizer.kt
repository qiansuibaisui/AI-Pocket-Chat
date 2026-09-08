package com.situ.aichat.data.remote.llm

/**
 * [zCODE] P1·解析容错层（需求文档 v3 第 1 项）：所有模型输出进入下游前的统一清洗。
 *
 * 两个职责：
 * 1. **泄漏闭合标签剥离**——模型（MiniMax 实测等）偶发把角色协议的闭合标记如 `[/dialogue]` 原样吐进正文，
 *    此层用正则剥除，绝不进聊天气泡。剥离对象是「非协议闭合形态」的 `[/name]`：App 自有协议标记
 *    （[CALENDAR_ACTION] / [offline_invite|…] / [future_meeting]{…} / [promise]{…}）**没有** `[/name]`
 *    闭合形态，故默认全剥零误伤；若未来（第 2 项锚点块等）引入带闭合形态的协议，把闭合名登记进
 *    [protectedTagNames] 即受保护。
 * 2. **容错块解析入口**（[parseBlockTolerantly]）——锚点/结构块「格式畸变不炸、能剥离能降级」的统一接口，
 *    供第 2 项锚点中央登记直接复用。现状四标记解析器（CalendarAction / OfflineMeetingAction /
 *    FutureMeetingTool / PromiseChatTool）已各自容错（runCatching / 纯 regex，见各自实现），P1 不改其调用。
 *
 * 流式挂载顺序（评审附加条件 a·锁定）：SSE 行 → [ThinkTagParser]（剥 `<think>`）→ [StreamSanitizer]
 * （剥泄漏闭合标签）。ThinkTagParser 先行——思考域（Reasoning）不进可见气泡、无泄漏风险，清洗只作用于
 * 正文域（Content）；该组合顺序是协议契约，改动须过 P1 单测（OutputSanitizerTest）。
 *
 * 纯逻辑零 Android 依赖 → 纯 JVM 单测覆盖。
 */
object OutputSanitizer {

    /** 泄漏闭合标签：`[/name]`，name 以字母开头、限长 32（字母/数字/下划线/连字符/空格）。 */
    private val LEAKED_CLOSING_TAG = Regex("\\[/([A-Za-z][A-Za-z0-9_\\- ]{0,31})\\]")

    /** 流中断可能留下的截断残留：行尾悬着的 `[/word`（无 `]` 收口）。 */
    private val TRUNCATED_TAG = Regex("\\[/[A-Za-z0-9_\\- ]*\\z")

    /** 受保护闭合名（默认空，见类注释；登记即保留原样不剥）。 */
    private val protectedTagNames: Set<String> = emptySet()

    /** 全量清洗结果（[sanitizeFull]）。 */
    data class SanitizedText(val text: String, val removedCount: Int)

    /**
     * 全量清洗（非流式统一出口用，评审裁决①挂 LlmClient.completion 返回处）。
     * 后台 JSON 产物（摘要/分析等）不含 `[/name]` 形态，零波及。
     */
    fun sanitizeFull(text: String): SanitizedText {
        var removed = 0
        val cleaned = LEAKED_CLOSING_TAG.replace(text) { m ->
            if (m.groupValues[1].trim() in protectedTagNames) m.value else { removed++; "" }
        }
        // 截断残留（半个泄漏标签悬在结尾）一并清掉。
        val truncated = TRUNCATED_TAG.find(cleaned)
        var out = cleaned
        if (truncated != null && truncated.value.length > 2) {
            out = cleaned.substring(0, truncated.range.first)
            removed++
        }
        return SanitizedText(out, removed)
    }

    /**
     * 流式增量清洗（挂 LlmClient.streamChat 的 Content token，与 [ThinkTagParser] 同形状的状态机）：
     * 跨 chunk 的半个闭合标签（`[/dialo` + `gue]`）留在缓冲区等下一段；[flush] 收尾。
     * 非线程安全——单流协程内串行 parse/flush。
     */
    class StreamSanitizer {
        private val buffer = StringBuilder()

        /** 累计剥离数（含 flush 丢弃的截断残留），供流结束时的报告日志。 */
        var removedCount: Int = 0
            private set

        /** 解析增量正文，返回已确定安全的输出片段；尾部可能是半标签的部分留在缓冲区。 */
        fun parse(chunk: String): List<String> {
            buffer.append(chunk)
            val out = mutableListOf<String>()
            while (true) {
                val m = LEAKED_CLOSING_TAG.find(buffer) ?: break
                if (m.groupValues[1].trim() in protectedTagNames) {
                    // 受保护名 → 原样放行（含标记本身），继续扫其后
                    if (m.range.first > 0) out.add(buffer.substring(0, m.range.last + 1))
                    buffer.delete(0, m.range.last + 1)
                    continue
                }
                if (m.range.first > 0) out.add(buffer.substring(0, m.range.first))
                buffer.delete(0, m.range.last + 1)
                removedCount++
            }
            // 尾部疑似「跨 chunk 半标签」（`[/word` 无收口）→ 留在缓冲区，等下一段或 flush
            val partial = TRUNCATED_TAG.find(buffer)
            if (partial != null && partial.range.first > 0) {
                out.add(buffer.substring(0, partial.range.first))
                buffer.delete(0, partial.range.first)
            }
            return out
        }

        /** 流结束：交还剩余正文；若剩余恰是截断残留（半个泄漏标签）则丢弃并计数。 */
        fun flush(): String {
            val rest = buffer.toString()
            buffer.setLength(0)
            val truncated = TRUNCATED_TAG.find(rest)
            return if (truncated != null && truncated.range.first == 0 && rest.length > 2) {
                removedCount++
                ""
            } else {
                rest
            }
        }
    }

    /** 容错块解析结果：Ok = 正常解析；Degraded = 畸形降级（带原因，绝不抛）。 */
    sealed interface BlockParseResult<out T> {
        data class Ok<T>(val value: T) : BlockParseResult<T>
        data class Degraded(val reason: String) : BlockParseResult<Nothing>
    }

    /**
     * 容错块解析统一入口（第 2 项锚点块复用）：null 输入 / 解析抛异常 / 解析返回空 → Degraded。
     * [source] 仅用于 Degraded 原因串（日志口径），不参与逻辑。
     */
    inline fun <T> parseBlockTolerantly(raw: String?, source: String, extract: (String) -> T?): BlockParseResult<T> =
        when {
            raw == null -> BlockParseResult.Degraded("$source: null 输入")
            else -> runCatching { extract(raw) }.fold(
                onSuccess = { parsed ->
                    if (parsed != null) BlockParseResult.Ok(parsed)
                    else BlockParseResult.Degraded("$source: 解析返回空（格式畸变降级）")
                },
                onFailure = { BlockParseResult.Degraded("$source: ${it.javaClass.simpleName}") },
            )
        }
}
