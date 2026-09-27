package com.situ.aichat.prompt

/**
 * 1:1 port of iOS `PromptBuilder+Parsing.swift`. Parses & cleans LLM replies: mood extraction, pet speech,
 * think-tag / DSML / meta-cognitive CoT stripping, parenthetical narration removal, system-directive leak
 * filtering, character-name-prefix stripping, excessive-repetition collapse.
 *
 * Post-process order is order-sensitive (see iOS §1.4): extractPetSpeech → parseMood → sanitize.
 * Regexes are ported verbatim; ICU `\z` / `[\s\S]` are supported by `java.util.regex`. **引擎差异（H3#0）**：
 * 裸 `\p{Han}` 仅 Android(ICU) 接受、JVM 单测 PatternSyntaxException → 统一写 `\p{script=Han}`
 * （两引擎等价语义；同 14.3c 正则引擎差异处理先例）。
 */
object ReplyParser {

    /** parseMood 结果。colorName 归一化为 green/yellow/red（对齐 iOS Color.mood + moodName 往返）。 */
    data class MoodResult(
        val cleanText: String,
        val colorName: String,
        val text: String,
        val emoji: String,
    )

    // MARK: - 缓存正则

    private val moodRegex =
        Regex("""\[(?:mood|情绪):([^|\]\n]{1,16})\|([a-zA-Z]+)\|([^\]\n]{1,40})\]""")

    private val petSpeechRegex = Regex("""\[PET:(.+?)\]""")

    /** 通用内部标签（始终清理）。 */
    private val internalTagRegexes: List<Regex> = listOf(
        Regex("""\[(?:mood|情绪):[^\]]+\]"""),
        Regex("""\[\d{4}(?:年|[-/])\d{1,2}(?:月|[-/])\d{1,2}(?:日)?\s*\d{1,2}:\d{2}\]"""),
        Regex("""\[CALENDAR_ACTION\][\s\S]*?\[/CALENDAR_ACTION\]"""),
        Regex("""<(?:\s*\|?\s*)?(?:/\s*\|?\s*)?DSML\s*\|?\s*[^>]*>"""),
        Regex("""\[offline_invite\|[^\]]+\]"""),
        Regex("""\[offline_end\]"""),
        // [zCODE] LB-6b：JSON 控制载荷原 regex 移至 stripJsonControlPayloadLines（行级过滤更干净——原 regex 剥后残留空行）
    )

    /**
     * 历史时间分割线 echo：LLM 偶尔会模仿上下文里 [HistoryTimeDivider] 注入的 `【时间 · …】` 系统分割线，
     * 整行剥除以防穿帮进气泡 / 入库 / 污染记忆。只命中「整行就是 `【时间 · …】`」的形状（行首 `【时间 ·` +
     * 行尾 `】`），不误伤把【…】用在句中、或其它【标签】、或【时间·…】后还有正文的正常内容。
     */
    private val historyTimeDividerEchoRegex =
        Regex("""(?m)^[ \t]*【时间 ·[^】\n]*】[ \t]*$""")

    /** 思考模型标签（无条件、不区分大小写；用 (?:闭合|\z) 兜底未闭合）。 */
    private val thinkingTagRegexes: List<Regex> = listOf(
        Regex("""<think>[\s\S]*?(?:</think>|\z)""", RegexOption.IGNORE_CASE),
        Regex("""<thinking>[\s\S]*?(?:</thinking>|\z)""", RegexOption.IGNORE_CASE),
        Regex("""<\|think\|>[\s\S]*?(?:<\|/think\|>|\z)""", RegexOption.IGNORE_CASE),
        Regex("""<thought>[\s\S]*?(?:</thought>|\z)""", RegexOption.IGNORE_CASE),
        Regex("""<reasoning>[\s\S]*?(?:</reasoning>|\z)""", RegexOption.IGNORE_CASE),
    )

    /** 元层思考小节标题词库（中英推理框架词，不收普通名词）。匹配前统一 lowercase。 */
    private val metaThinkingKeywords: Set<String> = setOf(
        "草稿", "草案", "方案", "选项", "步骤", "思路", "策略",
        "决定", "决策", "回应", "回复策略", "分析", "评估",
        "判断", "推理", "思考", "构思", "考量", "动机",
        "最终", "结论", "总结", "行动", "意图",
        "draft", "option", "step", "strategy", "decision",
        "analysis", "reasoning", "thought", "plan", "action",
        "final", "approach", "consideration", "rationale",
        "intent", "conclusion",
    )

    /** 匹配 markdown 加粗小节标题 `**XXX**：`/`**XXX**:`（^ 匹配每行行首）。 */
    private val metaCognitiveSectionStartRegex =
        Regex("""^\s*\*\*([^*\n]{1,30}?)\*\*\s*[:：]""", RegexOption.MULTILINE)

    /** 系统指令泄漏检测（行级，不区分大小写）。 */
    private val systemDirectiveLinePatterns: List<Regex> = listOf(
        Regex("""^【(?:节拍状态|历史提示|系统提示|时间提示|场景状态|系统|指令|提示|重要|Beat\s*Status|System|Note)】""", RegexOption.IGNORE_CASE),
        Regex("""^(?:请)?始终以该角色的?身份和?语气回复""", RegexOption.IGNORE_CASE),
        Regex("""^用当前\s*(?:App|app|应用)?\s*语言回复""", RegexOption.IGNORE_CASE),
        Regex("""^保持角色一致性""", RegexOption.IGNORE_CASE),
        Regex("""^Always reply in this character'?s identity""", RegexOption.IGNORE_CASE),
        Regex("""^Reply in the current app language""", RegexOption.IGNORE_CASE),
        Regex("""^(?:Keep|Keeping) the portrayal consistent""", RegexOption.IGNORE_CASE),
    )

    /** 老路径 A 首行时间戳前缀剥离。 */
    private val legacyPathATimestampHeaderRegex =
        Regex("""^\s*\[\d{4}-\d{1,2}-\d{1,2}\s+\d{1,2}[:：]\d{2}\]\s*\n?""")

    /** 括号叙事剥离（仅非线下+非见面消息）：全角 21+ 字；半角含中文且 20+ 字。 */
    private val parentheticalNarrationRegexes: List<Regex> = listOf(
        Regex("""（[^）]{21,300}）"""),
        Regex("""\([^)]{20,300}?[\p{script=Han}][^)]{0,300}?\)"""),
    )

    /** 线下叙事标签（非线下模式清理）。 */
    private val offlineNarrativeTagRegexes: List<Regex> = listOf(
        Regex("""\[/?(?:叙述|对话|内心|你|过渡|环境|动作|情绪|时间)\]"""),
        Regex("""\[场景[：:][^\]]*\]"""),
        Regex("""\[时间[：:][^\]]*\]"""),
    )

    /** MiniMax speech-2.8 语气插值标签白名单 + 暂停标签。 */
    private val miniMaxVoiceTagRegexes: List<Regex> = run {
        val tokens = listOf(
            "laughs", "sighs", "coughs", "clear-throat", "groans", "breath",
            "pant", "inhale", "exhale", "gasps", "sniffs", "snorts",
            "burps", "lip-smacking", "humming", "hissing", "emm", "sneezes",
        ).joinToString("|")
        listOf(
            Regex("""\(\s*(?:$tokens)\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""<#\d+(?:\.\d+)?#>""", RegexOption.IGNORE_CASE),
        )
    }

    private val dsmlBlockRegex =
        Regex("""<(?:\s*\|?\s*)DSML\s*\|?\s*function_calls\s*>[\s\S]*?<(?:\s*/\s*\|?\s*)DSML\s*\|?\s*function_calls\s*>""")

    private val collapseNewlinesRegex = Regex("""\n{3,}""")

    // MARK: - 情绪解析

    /**
     * 解析并移除情绪标记 `[mood:emoji|color|desc]`。取**最后一个** match。
     * @param preserveOfflineTags true 保留线下叙事标签
     * @param preserveMiniMaxVoiceTags true 保留 MiniMax 语气标签
     */
    fun parseMood(
        response: String,
        preserveOfflineTags: Boolean = false,
        preserveMiniMaxVoiceTags: Boolean = false,
    ): MoodResult {
        val matches = moodRegex.findAll(response).toList()

        var latestEmoji = ""
        var latestDesc = ""
        var latestColor = "green"
        for (match in matches) {
            val emoji = match.groupValues[1]
            val colorStr = match.groupValues[2]
            val desc = match.groupValues[3]
            latestEmoji = emoji
            latestDesc = desc
            latestColor = normalizeMoodColor(colorStr)
        }

        val cleanText = moodRegex.replace(response, "").trim()
        val sanitizedText = stripInternalAssistantTags(cleanText, preserveOfflineTags, preserveMiniMaxVoiceTags)

        return if (latestDesc.isEmpty()) {
            MoodResult(sanitizedText, "green", "", "")
        } else {
            MoodResult(sanitizedText, latestColor, latestDesc, latestEmoji)
        }
    }

    /** 对齐 iOS Color.mood(from:) + moodName(from:) 往返：归一化为 green/yellow/red。 */
    private fun normalizeMoodColor(name: String): String = when (name.lowercase()) {
        "red" -> "red"
        "yellow" -> "yellow"
        else -> "green"
    }

    // MARK: - 宠物发言提取（必须在 parseMood 之前调用）

    /** 提取 `[PET:内容]`；内容 ≤30 字否则忽略。返回 (清理后文本, 宠物发言?)。 */
    fun extractPetSpeech(response: String): Pair<String, String?> {
        val match = petSpeechRegex.find(response) ?: return Pair(response, null)
        val speech = match.groupValues[1].trim()
        val cleaned = petSpeechRegex.replace(response, "").trim()
        return if (speech.isEmpty() || speech.length > 30) {
            Pair(cleaned, null)
        } else {
            Pair(cleaned, speech)
        }
    }

    // MARK: - 文本清理

    /** 剥离内部标签（思考标签 → 元认知 CoT → DSML → internalTags → 线下叙事 → MiniMax → JSON 控制载荷 → 压缩换行）。 */
    fun stripInternalAssistantTags(
        content: String,
        preserveOfflineTags: Boolean = false,
        preserveMiniMaxVoiceTags: Boolean = false,
    ): String {
        var result = content
        for (regex in thinkingTagRegexes) result = regex.replace(result, "")
        result = stripMetaCognitiveBlocks(result)
        result = dsmlBlockRegex.replace(result, "")
        for (regex in internalTagRegexes) result = regex.replace(result, "")
        result = historyTimeDividerEchoRegex.replace(result, "")
        if (!preserveOfflineTags) {
            for (regex in offlineNarrativeTagRegexes) result = regex.replace(result, "")
        }
        if (!preserveMiniMaxVoiceTags) {
            result = stripMiniMaxVoiceTags(result)
        }
        // [zCODE] LB-6b：剥离独立成行的 JSON 控制载荷（{"type":"offline_end",…}——LB-6 标签形态的 JSON 变体）。
        // 形态匹配不做 JSON.parse 门槛（畸形载荷也要剥，容错优先）；只剥整行（首尾空白容忍），正文中间 JSON 不动。
        result = stripJsonControlPayloadLines(result)
        return result.replace("\n\n\n", "\n\n").trim()
    }

    /**
     * [zCODE] LB-6b：独立成行 JSON 控制载荷剥除。判定三条件（全部满足才剥）：
     * ①整行（允许首尾空白）以 `{` 开头以 `}` 结尾；②含控制语义字段名（type/finalMood/action 等）；
     * ③不含叙述性文字（纯键值形态）。连续多行载荷全剥、不堆积空行。正文中间的 JSON 字样不在此域（不剥）。
     */
    private val jsonControlPayloadLine = Regex(
        """^[ \t]*\{[ \t]*"[^"]+"[ \t]*:[ \t]*"[^"]*"(?:[ \t]*,[ \t]*"[^"]+"[ \t]*:[ \t]*"[^"]*")*[ \t]*\}[ \t]*$""",
        RegexOption.MULTILINE,
    )
    private val controlFieldNames = setOf("type", "finalMood", "action", "location", "activity", "invitation", "farewell", "changeKind")

    private fun stripJsonControlPayloadLines(content: String): String {
        // 逐行判定（比 MULTILINE 正则更精准——剥除时同步消费行尾换行，不留空行残留）
        return content.lines().filter { line ->
            val t = line.trim()
            if (!t.startsWith("{") || !t.endsWith("}")) return@filter true // 非 JSON 行不动
            if (!jsonControlPayloadLine.containsMatchIn(t)) return@filter true // 形态不符不动
            val hasControlField = controlFieldNames.any { "\"$it\"" in t }
            !hasControlField // 有控制字段 → false=过滤掉
        }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
    }

    fun stripMiniMaxVoiceTags(content: String): String {
        var result = content
        for (regex in miniMaxVoiceTagRegexes) result = regex.replace(result, "")
        return result
    }

    /**
     * 剥离 markdown 加粗格式的"内部思考过程"块（结构 + 语义双重判定）。
     * 1. 结构：连续 ≥3 个 `**XXX**：` 小节（相邻间隔 ≤5 行）
     * 2. 语义：标题里 ≥2 个命中词库
     */
    fun stripMetaCognitiveBlocks(content: String): String {
        if (content.isEmpty()) return content
        val lines = content.split("\n")
        if (lines.size < 3) return content

        data class Section(val lineIndex: Int, val title: String, val titleHitsKeyword: Boolean)
        val sections = mutableListOf<Section>()
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val match = metaCognitiveSectionStartRegex.find(trimmed) ?: continue
            val title = match.groupValues[1].trim()
            if (title.isEmpty() || title.length > 12) continue
            sections.add(Section(idx, title, metaKeywordHit(title)))
        }

        if (sections.size < 3) return content

        // 聚簇（相邻间隔 ≤5 行）
        val clusters = mutableListOf<MutableList<Section>>()
        var current = mutableListOf(sections[0])
        for (s in sections.drop(1)) {
            if (s.lineIndex - current.last().lineIndex <= 5) {
                current.add(s)
            } else {
                clusters.add(current)
                current = mutableListOf(s)
            }
        }
        clusters.add(current)

        val linesToRemove = mutableSetOf<Int>()
        for (cluster in clusters) {
            if (cluster.size < 3) continue
            val hits = cluster.count { it.titleHitsKeyword }
            if (hits < 2) continue

            val startIdx = cluster.first().lineIndex
            var endIdx = cluster.last().lineIndex
            var probe = endIdx + 1
            while (probe < lines.size) {
                if (lines[probe].trim().isEmpty()) break
                endIdx = probe
                probe += 1
            }
            for (i in startIdx..endIdx) linesToRemove.add(i)
        }

        if (linesToRemove.isEmpty()) return content

        val kept = lines.filterIndexed { idx, _ -> idx !in linesToRemove }
        val joined = kept.joinToString("\n")
        return collapseNewlinesRegex.replace(joined, "\n\n").trim()
    }

    private fun metaKeywordHit(title: String): Boolean {
        val lower = title.lowercase()
        return metaThinkingKeywords.any { lower.contains(it) }
    }

    /** 检测一行是否是泄漏的系统指令。 */
    fun isSystemDirectiveLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return systemDirectiveLinePatterns.any { it.containsMatchIn(trimmed) }
    }

    /** 清理 assistant 历史消息中被复读的系统指令（打断污染循环）。 */
    fun decontaminateAssistantContent(content: String): String {
        if (isTruncatedBracketFragmentOnly(content)) return ""

        val withoutLegacyHeader = legacyPathATimestampHeaderRegex.replace(content, "")
        // A8：历史里若有 AI 早先模仿吐出的 `【时间 · …】` echo（入库前 sanitize 漏网的旧残留），再喂回时一并剥除。
        val withoutDividerEcho = historyTimeDividerEchoRegex.replace(withoutLegacyHeader, "")

        val lines = withoutDividerEcho.split("\n")
        val cleaned = lines.filter { !isSystemDirectiveLine(it) }
        return cleaned.joinToString("\n")
            .replace("\n\n\n", "\n\n")
            .trim()
    }

    private fun isTruncatedBracketFragmentOnly(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > 30) return false
        val startsWithBracket = trimmed.startsWith("[") || trimmed.startsWith("【")
        if (!startsWithBracket) return false
        val hasClosing = trimmed.contains("]") || trimmed.contains("】")
        return !hasClosing
    }

    /** 剥离 assistant 历史的括号叙事（仅非线下+非见面消息时调用）。 */
    fun stripAssistantParentheticalNarration(content: String): String {
        var result = content
        for (regex in parentheticalNarrationRegexes) result = regex.replace(result, "")
        return collapseNewlinesRegex.replace(result, "\n\n").trim()
    }

    /**
     * 清理 assistant 回复：内部标签 + 元叙事行 + 角色名前缀 + 格式规范化。
     * @param characterName 传入后清除回复中的「角色名:」前缀
     */
    fun sanitizeAssistantResponse(
        content: String,
        preserveOfflineTags: Boolean = false,
        characterName: String? = null,
        preserveMiniMaxVoiceTags: Boolean = false,
    ): String {
        var sanitized = stripInternalAssistantTags(content, preserveOfflineTags, preserveMiniMaxVoiceTags)
        sanitized = sanitized.replace("\r\n", "\n")

        if (!characterName.isNullOrEmpty()) {
            sanitized = stripCharacterNamePrefix(sanitized, characterName)
        }

        // 极简回复兜底用：行过滤前、已剥内部标签 / 角色名前缀的内容快照。
        val preFilterContent = sanitized

        val lines = sanitized.split("\n")
        val cleanedLines: List<String> = if (preserveOfflineTags) {
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                if (isSystemDirectiveLine(trimmed)) return@mapNotNull null
                trimmed
            }
        } else {
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                if (isSystemDirectiveLine(trimmed)) return@mapNotNull null
                if (MessageSplitter.isMetaLine(trimmed)) return@mapNotNull null
                if (MessageSplitter.isQuoteOnlyLine(trimmed)) return@mapNotNull null
                trimmed
            }
        }

        sanitized = cleanedLines.joinToString("\n")
            .replace("\n\n\n", "\n\n")
            .trim()

        // 极简回复兜底：上面的 quote-only 行过滤会把“只有标点”的行当垃圾删掉；但当整条回复本身就是一个
        // 表达性极简回复（如 “？” / “？？？” / “...”）时，要原样保留成一条，而不是清空消失（= 用户诉求
        // “模型只回一个问号也要正常显示”）。孤立引号 " / 「」 这类纯标点垃圾仍丢弃。仅非线下路径需要——
        // 线下分支本就不过滤 quote-only。
        if (sanitized.isEmpty() && !preserveOfflineTags) {
            val minimal = preFilterContent.replace("\n", "").trim()
            if (isMeaningfulMinimalReply(minimal)) return minimal
        }

        sanitized = collapseExcessiveRepetition(sanitized)
        return sanitized
    }

    /** 表达性极简标点（合法极简回复的字符；区别于纯引号/括号垃圾）。均属 [MessageSplitter] 标点引号集的子集。 */
    private val minimalExpressivePunctuation = setOf('?', '？', '!', '！', '…', '。', '.')

    /**
     * 整条回复是否是“合法的表达性极简回复”——全由标点/引号字符组成（= 会被 quote-only 行过滤清掉），但至少
     * 含一个表达性标点（如 ？/！/…/。）。供 [sanitizeAssistantResponse] 在过滤把整条清空时兜底保留，同时仍丢弃
     * 孤立引号 `"` / `「」` 这类纯标点垃圾（它们不含表达性标点）。
     */
    private fun isMeaningfulMinimalReply(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!MessageSplitter.isQuoteOnlyLine(text)) return false
        return text.any { it in minimalExpressivePunctuation }
    }

    // MARK: - 重复内容检测与清理

    /** 同一短语出现 ≥8 次且占比 ≥80% → 只留 1 次。阈值 8 保护中文叠词。 */
    private fun collapseExcessiveRepetition(text: String): String {
        if (text.length <= 50) return text

        val separators = listOf(':', '：', '\n', ',', '，', '。', '.', ';', '；')
        for (sep in separators) {
            val parts = text.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 8) continue

            val counts = mutableMapOf<String, Int>()
            for (part in parts) counts[part] = (counts[part] ?: 0) + 1
            val (mostFrequent, count) = counts.maxByOrNull { it.value }?.toPair() ?: continue

            val ratio = count.toDouble() / parts.size.toDouble()
            if (count >= 8 && ratio >= 0.8) {
                val unique = parts.filter { it != mostFrequent }
                if (unique.isEmpty()) return mostFrequent
                var seen = false
                val cleaned = parts.mapNotNull { part ->
                    if (part == mostFrequent) {
                        if (seen) return@mapNotNull null
                        seen = true
                    }
                    part
                }
                return cleaned.joinToString(sep.toString())
            }
        }
        return text
    }

    // MARK: - 角色名前缀清理

    /** 清除 LLM 模仿历史格式输出的「角色名:」前缀（含嵌套重复）。 */
    private fun stripCharacterNamePrefix(text: String, name: String): String {
        val lines = text.split("\n")
        val prefixes = listOf("$name:", "$name：", "$name :", "$name ：")

        val cleaned = lines.mapNotNull { line ->
            var current = line.trim()
            var didStrip = true
            while (didStrip) {
                didStrip = false
                for (prefix in prefixes) {
                    if (current.startsWith(prefix)) {
                        current = current.removePrefix(prefix).trim()
                        didStrip = true
                        break
                    }
                }
            }
            current.ifEmpty { null }
        }
        return cleaned.joinToString("\n")
    }
}
