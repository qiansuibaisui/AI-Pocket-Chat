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
     * 渲染【剧情位置】+【已执行事件】注入段。锚点 null 且清单空 → ""（整段不注入）。
     * 措辞分级（读取处4 同款口径）：fresh =「刚刚更新/刚更新」；aging =「N 小时前的信息」；stale 不注入位置行
     * （按位置不明处理），只保留补齐提示（裁决2：仅 stale 时提）。
     */
    fun buildModule(
        anchor: StoryAnchorSnapshotEntity?,
        completedEvents: List<StoryEventLedgerEntity>,
        nowMillis: Long,
    ): String {
        val anchorLines = buildList {
            if (anchor != null) {
                when (AnchorVocabulary.freshnessOf(anchor.effectiveAt, nowMillis)) {
                    AnchorVocabulary.FreshnessLevel.FRESH -> {
                        add("当前：${sanitizeAnchorText(anchor.eventName)}（${sanitizeAnchorText(anchor.locationRaw)}）·刚刚更新")
                    }
                    AnchorVocabulary.FreshnessLevel.AGING -> {
                        val hours = ((nowMillis - anchor.effectiveAt) / 3_600_000L).coerceAtLeast(1)
                        add("当前：${sanitizeAnchorText(anchor.eventName)}（${sanitizeAnchorText(anchor.locationRaw)}）·${hours}小时前的信息，按剧情判断是否仍成立")
                    }
                    AnchorVocabulary.FreshnessLevel.STALE -> Unit // 超龄：位置按不明处理，不注入旧位置行
                }
            }
        }
        val eventLines = completedEvents
            .sortedByDescending { it.completedAt }
            .take(5) // 软上限：清单是「近期」不是流水账
            .map { "- ${sanitizeAnchorText(it.description.ifBlank { it.eventKey })}" }
        if (anchorLines.isEmpty() && eventLines.isEmpty()) return ""
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
            // [zCODE] B2·场景治理规则（用户裁定版）：场景节点允许自然推进，但每次变更必须用 [场景：…] 标注并说明
            // 缘由；回忆不得篡改既定结果。常驻注入（与锚点双空时整段不出——双空=无场景治理需求）。
            appendLine("【场景规则】场景与位置允许自然推进；若你的位置或所处场景发生变化，回复中须用一行 [场景：当前地点·当前时间] 标注并顺带说明缘由；回忆过去时不得改写上述已执行事件的结果。")
        }.trimEnd('\n')
    }
}
