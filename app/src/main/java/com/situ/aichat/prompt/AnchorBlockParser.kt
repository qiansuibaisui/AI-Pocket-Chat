package com.situ.aichat.prompt

import com.situ.aichat.data.remote.llm.OutputSanitizer

/**
 * [zCODE] P1·第2项 锚点中央登记：对话内锚点块解析器（评审点1·写入通道①）。
 *
 * 识别两形态（均为模型已在输出的现有格式，非新增协议）：
 * 1. 行内标记 `[场景：地点·时间]`（线下叙事同款，OfflineContentParser:98 的变体容忍口径）；
 * 2. 块标记 `【场景状态】` 起始的段落，段内行容忍 `地点：/位置：/事件：` 键值或自由文本首行。
 *
 * 全部候选择一经 [OutputSanitizer.parseBlockTolerantly]（P0-第1项容错层复用落点）：畸形降级跳过 + 计数，
 * 绝不炸回合。**只读不剥**——正文剥离维持 ReplyParser 既有职责（stripInternalAssistantTags/meta 剥离已覆盖
 * 两形态防漏进气泡），本解析器与剥离互不干扰（评审点1 边界：非破坏性提取）。
 *
 * 纯函数 → 纯 JVM 单测（AnchorBlockParserTest）。
 */
object AnchorBlockParser {

    /** 解析出的锚点块（原始字段，归一在 Repository 侧做）。 */
    data class AnchorBlock(
        /**
         * 位置原始整值（LB-4 规格：= "地点："与下一个"｜"之间的**原始子串**——值内含 ·（如船名
         * "雷德·佛斯号"）时绝不被切分；· 仅在 locationKey 层用于船团基线前缀比较，禁止参与字段切分与显示重组，
         * 显示端按原始整值渲染）。
         */
        val locationRaw: String,
        val eventName: String = "",
        val timeText: String? = null,
        /** 在场名单（仅规范式 `｜{名}：在场/不在场（{位置}）` 段提取；其余形态空——系统不做会话参与人推断）。 */
        val presentList: List<PresentEntry> = emptyList(),
    )

    /** 在场名单条目（规范 v1 的 ｜ 段）。 */
    data class PresentEntry(val name: String, val present: Boolean, val location: String = "")

    /** 行内 `[场景：a·b]` / `[场景：a·b·c]`——段间用 · 或 • 或 | 分隔，首段=地点，末段若像时间则记 timeText。 */
    private val INLINE_TAG = Regex("""\[场景[：:]\s*([^\]]+)]""")

    /** 块头 `【场景状态】`（ReplyParser:76 系统指令段同款标题变体容忍；**可带行内余文**——「【场景状态】甲板上」单行式）。 */
    private val BLOCK_HEADER = Regex("""^【(?:场景状态|当前场景|场景)】\s*$""", RegexOption.IGNORE_CASE)
    private val BLOCK_HEADER_INLINE = Regex("""^【(?:场景状态|当前场景|场景)】\s*(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * [zCODE] LB-3-B·内嵌/规范单行形态锚点：`【锚点】` 出现在**行中任意位置**（破折号引导 `————【锚点】…`、
     * 非独立块、混入叙事尾部，及规范 v1 独立单行）。
     *
     * **分隔符契约（正式·点3 锁金样）**：`【锚点】{个人位置·船团基线}｜{他人}：在场/不在场（{位置}）`——
     * `｜` 分隔位置段与在场名单；`：` 引导在场态。**LB-4 规格澄清**：位置段 = 至下一个 `｜`/行尾的**原始整值**，
     * `·` 不参与切分（船团基线匹配在读取层做前缀比较）。
     */
    private val EMBEDDED_ANCHOR = Regex("""【锚点】\s*([^｜\n\r]*)""")

    /** 在场名单段：`{名}：在场` / `{名}：不在场（{位置}）`。 */
    private val PRESENT_ENTRY = Regex("""([^：｜\n\r]+?)：(在场|不在场)(?:（([^）]*)）)?""")

    /** 痕迹超集（含畸形/未闭合形态）：任意锚点标记出现即计——解析全败时有痕迹可依（LB-3-B ③ 前提）。 */
    private val TRACE_MARKS = Regex("""\[场景[：:]|【锚点】|【(?:场景状态|当前场景|场景)】""")

    /** 锚点**痕迹**计数（LB-3-B ③：解析全败但有痕迹 → 调用侧记空+日志，不静默丢弃）。任何形态（含畸形/未闭合）的锚点标记出现次数。 */
    fun anchorTraceCount(response: String): Int = TRACE_MARKS.findAll(response).count()

    /** 块内键值行（容忍全角冒号与「位置/地点/所在」同义）。 */
    private val KEY_LOCATION = Regex("""^(?:地点|位置|所在)[：:]\s*(.+)$""")
    private val KEY_EVENT = Regex("""^(?:事件|正在|动态|近况)[：:]\s*(.+)$""")

    /** 从完整回复中提取锚点块（多个时取最后一个 = 模型最终修正的落点）。 */
    fun parseLastBlock(response: String): AnchorBlock? = parseBlocks(response).lastOrNull()

    /** 全量提取（历史/调试用；主链路只消费最后一个）。输出按**文中出现位置**排序——多形态混排时"最后输出"= 真正的模型最终落点。 */
    fun parseBlocks(response: String): List<AnchorBlock> {
        val out = mutableListOf<Pair<Int, AnchorBlock>>() // (文中偏移, 块)——结尾统一按位置排序
        // ① 行内标记（逐个容错：畸形段静默跳过该段，不炸整块）
        for (m in INLINE_TAG.findAll(response)) {
            val parsed = OutputSanitizer.parseBlockTolerantly(m.groupValues[1], "anchor-inline") { raw ->
                val parts = raw.split('·', '•', '|', '｜').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) null else {
                    // 末段像时间（纯数字/含「点/时/刻/早/午/晚/黄昏/夜/晨」）→ 拆为 timeText
                    val last = parts.last()
                    val looksLikeTime = last.length <= 12 && Regex("""[\d点时:：分刻早午晚黄昏夜晨凌晨]|黄昏|黎明|深夜""").containsMatchIn(last)
                    if (parts.size >= 2 && looksLikeTime) {
                        AnchorBlock(locationRaw = parts.first(), eventName = parts.drop(1).dropLast(1).joinToString("·"), timeText = last)
                    } else {
                        AnchorBlock(locationRaw = parts.first(), eventName = parts.drop(1).joinToString("·"))
                    }
                }
            }
            if (parsed is OutputSanitizer.BlockParseResult.Ok) out.add(m.range.first to parsed.value)
        }
        // ②b 内嵌/规范单行形态（LB-3-B + 点3 归一）：`————【锚点】甲板·白团旗舰｜贝克曼：在场` ——
        // 位置段 = 标记后至 ｜/行尾的【原始整值】（LB-4：· 不切分·船名"雷德·佛斯号"保持整值），｜后在场名单提取。
        for (m in EMBEDDED_ANCHOR.findAll(response)) {
            val presentPart = response.substring(m.range.last + 1).substringBefore('\n')
            val presentEntries = PRESENT_ENTRY.findAll(presentPart).mapNotNull { pm ->
                val name = pm.groupValues[1].trim()
                if (name.isEmpty()) null else PresentEntry(
                    name = name,
                    present = pm.groupValues[2] == "在场",
                    location = pm.groupValues[3].trim(),
                )
            }.take(6).toList()
            val parsedEmbedded = OutputSanitizer.parseBlockTolerantly(m.groupValues[1], "anchor-embedded") { raw ->
                val body = raw.trim().trimEnd('。', '，', '！', '？', '；', '，', ' ', '，')
                if (body.isEmpty()) null else AnchorBlock(locationRaw = body, presentList = presentEntries)
            }
            if (parsedEmbedded is OutputSanitizer.BlockParseResult.Ok && parsedEmbedded.value.locationRaw.isNotEmpty()) {
                out.add(m.range.first to parsedEmbedded.value)
            }
        }
        // ② 块标记（状态机：块头之后的非空行里找键值；连续两行非键值非空即视为块结束）
        val lines = response.lines()
        // 行号→全文偏移（块式块头位置排序键）
        fun lineOffset(index: Int): Int = lines.take(index).sumOf { it.length + 1 }
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            // 单行式「【场景状态】甲板上」：余文直接作地点（容忍最常见的偷懒输出）
            val headerInline = BLOCK_HEADER_INLINE.find(line)
            if (headerInline != null && !BLOCK_HEADER.matches(line)) {
                val parsedInline = OutputSanitizer.parseBlockTolerantly(headerInline.groupValues[1], "anchor-block-inline") { raw ->
                    AnchorBlock(locationRaw = raw.trim())
                }
                if (parsedInline is OutputSanitizer.BlockParseResult.Ok) out.add(lineOffset(i) to parsedInline.value)
                i++
                continue
            }
            if (!BLOCK_HEADER.matches(line)) { i++; continue }
            var loc: String? = null
            var event: String? = null
            val headerStartOffset = lineOffset(i) // 块头行位置（排序键）
            var j = i + 1
            var stray = 0
            while (j < lines.size) {
                val line = lines[j].trim()
                if (line.isEmpty()) { j++; continue }
                val isHeader = BLOCK_HEADER.matches(line) || line.startsWith("[") || line.startsWith("【")
                if (isHeader) break
                val kl = KEY_LOCATION.find(line)
                val ke = KEY_EVENT.find(line)
                when {
                    kl != null -> loc = kl.groupValues[1].trim()
                    ke != null -> event = ke.groupValues[1].trim()
                    else -> {
                        stray++
                        // 首个杂行兜底当地点（「【场景状态】甲板上」单行式），第二个杂行即出块
                        if (loc == null && stray == 1) loc = line else break
                    }
                }
                j++
            }
            if (loc != null) {
                val block = OutputSanitizer.parseBlockTolerantly(loc!!, "anchor-block") { raw ->
                    AnchorBlock(locationRaw = raw.trim(), eventName = event?.trim().orEmpty())
                }
                if (block is OutputSanitizer.BlockParseResult.Ok) out.add(headerStartOffset to block.value)
            }
            i = j
        }
        return out.sortedBy { it.first }.map { it.second }
    }
}
