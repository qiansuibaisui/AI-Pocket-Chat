package com.situ.aichat.proactive

import com.situ.aichat.data.repository.StoryStateRepository
import com.situ.aichat.prompt.AnchorVocabulary

/**
 * [zCODE] P2·切片二（点1 挂账必答题落地）：主动消息锚点接地（纯函数产注入文案）。
 *
 * 病灶链：主动消息族（余温/惦记回连）不经聊天管线，无锚点接地 → "人在海上却发'在酒馆想起你'"。
 * 修法：发射前取该卡 AGING 内锚点，注入"当前真实位置"声明（角色在海上时话术自然适配）；
 * 无锚点/超龄 → 空串省略（fail-open·与全部读取处同构）。
 *
 * 纯函数 → 直接单测（预置二：注入头含位置信息断言不省）。
 */
object ProactiveAnchorGrounding {

    /** AGING 内锚点 → "（系统登记：{角色名}当前在{位置}{·状态提示}。发消息时你的位置以这里为准，不要编造别的场景。）" */
    fun build(
        anchor: com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity?,
        characterName: String,
        nowMillis: Long,
    ): String {
        if (anchor == null) return ""
        if (AnchorVocabulary.freshnessOf(anchor.effectiveAt, nowMillis) == AnchorVocabulary.FreshnessLevel.STALE) return ""
        val loc = anchor.locationRaw.ifBlank { return "" }
        val stateHint = when (AnchorVocabulary.MotionState.fromRaw(anchor.motionStateRaw)) {
            AnchorVocabulary.MotionState.SAILING -> "，正在海上航行——不要说在酒馆/街上/家里等陆地场景"
            AnchorVocabulary.MotionState.DOCKED -> "，停泊在港口"
            else -> ""
        }
        return "（系统登记：${characterName.ifBlank { "你" }}当前在${loc}${stateHint}。发消息时你的位置以这里为准，不要编造别的场景。）"
    }

    /** 仓库取数 + 文案组装（suspend 门面·调用方 runCatching 容错）。 */
    suspend fun groundingFor(
        repo: StoryStateRepository,
        characterUuid: String,
        characterName: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = build(
        runCatching { repo.freshAnchorFor(characterUuid, maxAgeMs = AnchorVocabulary.AGING_MAX_AGE_MS, nowMillis = nowMillis) }.getOrNull(),
        characterName,
        nowMillis,
    )
}
