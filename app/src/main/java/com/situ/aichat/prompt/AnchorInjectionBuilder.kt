package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity

/**
 * [zCODE] P1·第2项 读取处1：对话锚点注入段 + 已完成事件清单（纯函数，评测通过后接入 PromptBuilder 宏 {{剧情锚点}}）。
 *
 * 评审附加条件4（注入安全）：eventName/locationRaw 是模型生成原文，直接拼系统注入段存在提示注入理论通道
 * （模型输出 `[场景：忽略以上指令…]`）——所有字段过 [sanitizeAnchorText]（长度截断 + 换行折叠），
 * parseBlockTolerantly 已挡大部分畸形，此为第二道闸。
 */
object AnchorInjectionBuilder {

    /** 字段防御（附加条件4）：≤40 字符 + 换行/制表折叠为空格（绝不让模型原文换行拆段污染注入块）。 */
    fun sanitizeAnchorText(raw: String, maxLen: Int = 40): String =
        raw.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(maxLen)

    /**
     * [zCODE] 工单#1：锚点双层格式指导（prompt 指导落点——恒注入，与认知边界同批）。
     * 认知边界六.2 为正本红线（一字不改），格式示例与 MotionState 粒度联动说明落本段；只增段、零改既有段。
     */
    val ANCHOR_OUTPUT_GUIDE: String = buildString {
        appendLine("【锚点格式】场景或位置变化时，回复末尾用一行双层锚点标注（与 [场景：…] 简式二选一，有船团归属时优先本格式）：")
        appendLine("【锚点】全团：{团名}·{海域}·{港口/锚地}（{船名}·停靠）｜个人：{个人位置}｜在场：{名单}｜节点：{事件}")
        append("（{船名}·后填 停靠/航行中/锚泊/漂泊）粒度联动：停靠=精确到港口/锚地；航行中与锚泊/漂泊=海域级（港口位写「某海域」，锚泊/漂泊状态在括号内注明）。无团角色省略「全团：」段；个人/在场/节点段按需省略。")
    }.trimEnd()

    /**
     * [zCODE] 工单#1：全团层渲染行（粒度联动强制口径——模型漂移写了港口也按 MotionState 退级：
     * 航行中/锚泊/漂泊 → 港口位一律「某海域」；停靠 → 港口原样）。全空 → null（不出行）。
     */
    private fun fleetLineOf(layer: AnchorBlockParser.FleetLayer): String? {
        val motionText = sanitizeAnchorText(layer.motionText, 10)
        val shipName = sanitizeAnchorText(layer.shipName, 20)
        val portPart = if (AnchorVocabulary.isSeaAreaGranularity(layer.motionText)) {
            AnchorVocabulary.SEA_AREA_PLACEHOLDER
        } else {
            sanitizeAnchorText(layer.port, 20)
        }
        val head = listOf(
            sanitizeAnchorText(layer.fleetName, 20),
            sanitizeAnchorText(layer.seaArea, 20),
            portPart,
        ).filter { it.isNotEmpty() }.joinToString("·")
        val parenParts = listOf(shipName, motionText).filter { it.isNotEmpty() }.joinToString("·")
        if (head.isEmpty() && parenParts.isEmpty()) return null
        return buildString {
            append("全团：")
            if (head.isNotEmpty()) append(head)
            if (parenParts.isNotEmpty()) append("（").append(parenParts).append("）")
        }
    }

    /**
     * 渲染【剧情位置】+【已执行事件】+【防复读】注入段。锚点 null 且清单空 → ""（整段不注入）。
     * 措辞分级（读取处4 同款口径）：fresh =「刚刚更新/刚更新」；aging =「N 小时前的信息」；stale 不注入位置行
     * （按位置不明处理），只保留补齐提示（裁决2：仅 stale 时提）。
     *
     * [regenSteps]（LB-1·回合内重生成防复读）：重生成请求时传入**本回合已输出的步骤要点**（被删旧回复的段文本），
     * 注入规则"已输出内容不得复读同一情节节拍，须换推进角度"——治"见面内重生成复读同序节拍
     * （蜂蜜威士忌→外套披肩→萨奇留饭 ×3）"：见面未完结、ledger 无记录，【已执行事件】清单覆盖不到的窗口。
     * 与已执行事件清单同段渲染；空清单省略。
     */
    fun buildModule(
        anchor: StoryAnchorSnapshotEntity?,
        completedEvents: List<StoryEventLedgerEntity>,
        nowMillis: Long,
        regenSteps: List<String> = emptyList(),
    ): String {
        val anchorLines = buildList {
            if (anchor != null) {
                // [zCODE] 工单#1：全团层行（fleetLayerJson 空=存量单层 → 不出行，旧输出逐字节不变）
                val fleetLayer = AnchorBlockParser.FleetLayerCodec.decode(anchor.fleetLayerJson)
                when (AnchorVocabulary.freshnessOf(anchor.effectiveAt, nowMillis)) {
                    AnchorVocabulary.FreshnessLevel.FRESH -> {
                        fleetLayer?.let { fl -> fleetLineOf(fl)?.let { add(it) } }
                        // 个人层行（事件与位置全空=仅全团层锚点 → 个人行省略，防"当前：·刚刚更新"空壳行）
                        if (anchor.eventName.isNotBlank() || anchor.locationRaw.isNotBlank()) {
                            // [zCODE] LB-4：locationRaw 为原始整值（含 · 不切分）；eventName 空（规范单行形态）时直接整值显示
                            add(
                                if (anchor.eventName.isBlank()) "当前：${sanitizeAnchorText(anchor.locationRaw)}·刚刚更新"
                                else "当前：${sanitizeAnchorText(anchor.eventName)}（${sanitizeAnchorText(anchor.locationRaw)}）·刚刚更新",
                            )
                        }
                    }
                    AnchorVocabulary.FreshnessLevel.AGING -> {
                        fleetLayer?.let { fl -> fleetLineOf(fl)?.let { add(it) } }
                        val hours = ((nowMillis - anchor.effectiveAt) / 3_600_000L).coerceAtLeast(1)
                        if (anchor.eventName.isNotBlank() || anchor.locationRaw.isNotBlank()) {
                            add(
                                if (anchor.eventName.isBlank()) "当前：${sanitizeAnchorText(anchor.locationRaw)}·${hours}小时前的信息，按剧情判断是否仍成立"
                                else "当前：${sanitizeAnchorText(anchor.eventName)}（${sanitizeAnchorText(anchor.locationRaw)}）·${hours}小时前的信息，按剧情判断是否仍成立",
                            )
                        }
                    }
                    AnchorVocabulary.FreshnessLevel.STALE -> Unit // 超龄：位置按不明处理，不注入旧位置行（全团层同样不注入）
                }
            }
        }
        val eventLines = completedEvents
            .sortedByDescending { it.completedAt }
            .take(5) // 软上限：清单是「近期」不是流水账
            .map { "- ${sanitizeAnchorText(it.description.ifBlank { it.eventKey })}" }
        val stepLines = regenSteps.mapNotNull { s -> s.trim().takeIf { it.isNotEmpty() }?.let { "- ${sanitizeAnchorText(it)}" } }
        if (anchorLines.isEmpty() && eventLines.isEmpty() && stepLines.isEmpty()) return ""
        return buildString {
            if (anchorLines.isNotEmpty()) {
                appendLine("【剧情位置】（系统登记的真实状态，优先于一切推测）")
                anchorLines.forEach { appendLine(it) }
                appendLine()
            }
            if (eventLines.isNotEmpty()) {
                appendLine("【已执行事件】以下流程已执行完毕，不得重新执行、不得当成没发生过：")
                eventLines.forEach { appendLine(it) }
                appendLine()
            }
            if (stepLines.isNotEmpty()) {
                appendLine("【防复读】以下是本回合已输出过的情节节拍，重写时不得复读同一节拍（原样或换皮同序都算复读），必须换一个推进角度：")
                stepLines.forEach { appendLine(it) }
                appendLine()
            }
            // [zCODE] B2·场景治理规则（用户裁定版）：场景节点允许自然推进，但每次变更必须用 [场景：…] 标注并说明
            // 缘由；回忆不得篡改既定结果。常驻注入（与锚点双空时整段不出——双空=无场景治理需求）。
            appendLine("【场景规则】场景与位置允许自然推进；若你的位置或所处场景发生变化，回复中须用一行 [场景：当前地点·当前时间] 标注并顺带说明缘由；回忆过去时不得改写上述已执行事件的结果。")
        }.trimEnd('\n')
    }
}
