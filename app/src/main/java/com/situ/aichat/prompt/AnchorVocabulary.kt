package com.situ.aichat.prompt

/**
 * [zCODE] P1·第2项 锚点中央登记：节点命名规范化词表 + 时效分级（评审点1·裁决4 照单批准）。
 *
 * 职责一：把模型原文地点归一为 [MotionState]——全系统唯一的「靠港中/停泊中/航行中」判定源，杜绝漂移混用；
 * 词表不识 → [MotionState.UNKNOWN] + locationKey=null，下游按「位置不明」处理，不硬猜。
 * 已知歧义（评审备注·接受）：甲板→sailing 在停泊时不成立，歧义样本由 P3 手动修正通道兜底。
 *
 * 职责二：锚点时效分级常量（裁决1：6h/24h，评审定值可调——集中此处，不散落魔数）。
 *
 * 纯函数零依赖 → 纯 JVM 单测（AnchorVocabularyTest）。
 */
object AnchorVocabulary {

    /** 移动状态（raw 值即库/注入契约，重命名断历史数据）。 */
    enum class MotionState(val raw: String) {
        SAILING("sailing"),       // 航行中
        DOCKED("docked"),         // 停泊/靠港
        ASHORE("ashore"),         // 登陆/在岸
        INDOORS("indoors"),       // 室内
        IN_TRANSIT("in_transit"), // 移动中/赶路
        UNKNOWN("unknown");       // 词表不识——下游按「位置不明」处理

        companion object {
            fun fromRaw(raw: String): MotionState = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
        }
    }

    /**
     * 锚点时效分级（裁决1·评审定值可调）：
     * - [FRESH] ≤6h：可作日程/朋友圈的**硬约束**输入；
     * - [AGING] ≤24h：仅名片/近况等软参考；
     * - [STALE] >24h：一律按「位置不明」处理 + 触发提示补齐（裁决2：仅 stale 时提——
     *   从不输出锚点的模型经 carryover 使 effectiveAt 持续变老 → 等效每回合都提，正是补齐回路该有的行为）。
     */
    enum class FreshnessLevel { FRESH, AGING, STALE }

    /** 评审定值（可调）：fresh 上限 6 小时。 */
    const val FRESH_MAX_AGE_MS: Long = 6L * 60 * 60 * 1000

    /** 评审定值（可调）：aging 上限 24 小时。 */
    const val AGING_MAX_AGE_MS: Long = 24L * 60 * 60 * 1000

    fun freshnessOf(effectiveAt: Long, nowMillis: Long): FreshnessLevel {
        val age = nowMillis - effectiveAt
        return when {
            age <= FRESH_MAX_AGE_MS -> FreshnessLevel.FRESH
            age <= AGING_MAX_AGE_MS -> FreshnessLevel.AGING
            else -> FreshnessLevel.STALE
        }
    }

    /** 关键词表（顺序即优先级：先海后岸，防「港口的酒馆」被 indoors 抢判）。 */
    private val KEYWORDS: List<Pair<MotionState, Regex>> = listOf(
        MotionState.SAILING to Regex("航行|出海|在海上|海面上|甲板|船舱|桅杆"),
        MotionState.DOCKED to Regex("停泊|靠港|靠岸|下锚|泊|码头|港口"),
        MotionState.ASHORE to Regex("登陆|上岸|在岸|集市|街道|镇上|城里|酒馆|广场"),
        MotionState.INDOORS to Regex("寝室|家中|房间里|屋内|店内|宿舍|卧舱"),
        MotionState.IN_TRANSIT to Regex("移动中|赶路|前往|路上|途中|在路上"),
    )

    /** 归一结果：状态 + 归一 key（用首个命中的关键词本身，供下游一致比较）。 */
    data class Normalized(val state: MotionState, val key: String?)

    /** 地点原文 → (MotionState, locationKey)。不识 → (UNKNOWN, null)，绝不硬猜。 */
    fun normalize(locationRaw: String): Normalized {
        val trimmed = locationRaw.trim()
        if (trimmed.isEmpty()) return Normalized(MotionState.UNKNOWN, null)
        for ((state, pattern) in KEYWORDS) {
            val hit = pattern.find(trimmed)?.value
            if (hit != null) return Normalized(state, hit)
        }
        return Normalized(MotionState.UNKNOWN, null)
    }

    /**
     * [zCODE] P1·第2项 B1：锚点节点变更判定（MotionState 或归一 locationKey 变化）。carryover（内容原样延续）
     * 天然不触发；首次建档（prev=null）不算变更。纯函数供 Deliverer 通知与单测共用。
     */
    fun anchorChanged(
        prev: com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity?,
        next: com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity,
    ): Boolean {
        if (prev == null) return false
        return prev.motionStateRaw != next.motionStateRaw || prev.locationKey != next.locationKey
    }
}
