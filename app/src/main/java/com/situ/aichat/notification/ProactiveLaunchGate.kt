package com.situ.aichat.notification

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.morgans.NewsControlSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [zCODE] 中继3·项5(v2) C11 主动消息**发射前**硬闸门（终版 2026-10-02·母体规格书第4项）。
 *
 * 发射概率 = 基础频率（引擎既有：日程驱动槽位+30%随机·NotificationScheduler）× 人设主动度系数 × 关系门槛——
 * 本轮最小切面：**关系门槛=禁令一硬拦**（陌生卡不得发起·治 F3）+人设系数/沉默兜底**参数位**
 * （personaCoefficient≤0=人设静默·默认 1.0=现行为·P6"人设主动度"属性到位后接真值）；
 * 情报型 bypass 检查点=**本闸**（业主 Q④：情报型角色无视闸门·白名单默认空表=零 bypass·行为不变；
 * 知多/提前知/无视闸门三能力拆分语义留 P6 定）。
 *
 * 架构区分（四面各司其职）：A5 管日程侧、A6 管互动侧、**C11 管发射侧（本闸）**、分发面靠结构（不设关系闸）；
 * RelationGateService 本体零改原则维持。
 *
 * 落位：NotificationScheduler.scheduleInternal 单点（排程即发射预约——闸死排程=发射不可能，最硬拦截）。
 * 数据源：发射前读质感（关系值·CharacterEntity.relationshipQuality 八维 0-100）——认知矩阵供后续
 * "谁在场/谁得知什么"精细化（三代样本兼容），本切面判定=陌生卡（质感未动过=INITIAL 或空列）。
 */
@Singleton
class ProactiveLaunchGate @Inject constructor(
    private val controlSettings: NewsControlSettings,
) {

    /** 发射判定结果（allowed=false 时 reason 进日志·不炸调度——per-character 隔离既有纪律）。 */
    data class Decision(val allowed: Boolean, val reason: String)

    suspend fun canLaunch(character: CharacterEntity): Decision {
        val gate = runCatching { controlSettings.proactiveGate() }.getOrNull() ?: NewsControlSettings.ProactiveGate()
        // 默认 enabled=false=现行为（闸门随 F3 禁令由业主开启·拍板）
        if (!gate.enabled) return Decision(true, "发射闸未启用（默认=现行为）")
        // 情报型 bypass：发射闸检查点（默认空表=零 bypass）
        if (character.uuid in gate.intelWhitelist) return Decision(true, "情报型白名单 bypass")
        // 人设沉默兜底（参数位：P6 人设主动度属性到位前恒 1.0；≤0=静默人设不发起）
        if (gate.personaCoefficient <= 0.0) return Decision(false, "人设沉默兜底（主动度系数=${gate.personaCoefficient}）")
        // 禁令一：陌生卡不得发起（治 F3 实证病灶）——质感空列或未动过 INITIAL 基线=陌生
        if (gate.strangerBlock && isStrangerCard(character)) {
            return Decision(false, "禁令一：陌生卡不得发起（质感未建立）")
        }
        return Decision(true, "关系门槛通过")
    }

    /** 陌生卡判定：关系质感未建立——JSON 空列（新卡）或仍为 INITIAL 基线（建卡后零互动改写）。 */
    private fun isStrangerCard(character: CharacterEntity): Boolean =
        character.relationshipQualityJSON.isBlank() ||
            runCatching { character.relationshipQuality == RelationshipQuality.INITIAL }.getOrDefault(true)
}
