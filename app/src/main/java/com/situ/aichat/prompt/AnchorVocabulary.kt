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

    /** 归一结果：状态 + 归一 key（顶层规范岛名/船名 + 次层命中词，供下游一致比较）。 */
    data class Normalized(val state: MotionState, val key: String?)

    /** 地点原文 → (MotionState, locationKey)。不识 → (UNKNOWN, null)，绝不硬猜。 */
    fun normalize(locationRaw: String): Normalized {
        val trimmed = locationRaw.trim()
        if (trimmed.isEmpty()) return Normalized(MotionState.UNKNOWN, null)
        for ((state, pattern) in KEYWORDS) {
            val hit = pattern.find(trimmed)?.value
            if (hit != null) return Normalized(state, buildLocationKey(trimmed, hit))
        }
        return Normalized(MotionState.UNKNOWN, null)
    }

    // ── [zCODE] P2·岛名规范与 locationKey 三层顶层（P4 世界地图/P3 空间查询的唯一索引·终审裁决③一期常量表） ──
    //
    // 契约：locationRaw 原始整值不动（LB-4 不变）；归一 locationKey = "{顶层}·{次层命中词}"——**顶层必须规范**：
    // 规范岛名或船名（船 = 移动岛屿同层索引）。本期只保证顶层规范（三层齐全不强制）。
    // 岛名一期最小集按八大域分组（P4 地图管理面数据骨架·纯常量不做表）；备份序列化定性：**不进备份**
    //（常量表随 APK 版本演进，旧备份的 locationKey 照常恢复——key 是派生值可重算，非用户数据）。
    // 归一容错（fail-open）：顶层模糊匹配（包含式命中）；未命中（如"伟大航路某岛"——**不映射任何域**，
    // 终审必改①）保留原文 + location_unnormalized 日志，不阻塞落锚。历史锚点不回填清洗。

    /** 八大域岛名最小集（一期占位，P4 扩表）。 */
    val ISLAND_TOP_LEVELS: Map<String, List<String>> = linkedMapOf(
        "东海" to listOf("风车村", "谢尔兹镇", "罗格镇"),
        "西海" to listOf("奥哈拉", "伊利西亚"),
        "南海" to listOf("炼狱岛"),
        "北海" to listOf("露露西亚", "斯巴克"),
        "乐园" to listOf("阿拉巴斯坦", "磁鼓岛", "空岛", "水之都", "司法岛", "香波地", "恐怖三桅帆船", "鱼人岛"),
        "新世界" to listOf("庞克哈萨德", "德雷斯罗萨", "佐乌", "蛋糕岛", "和之国", "艾格赫德"),
        "无风带" to listOf("九蛇岛", "亚马逊百合"),
        "玛丽乔亚" to listOf("圣地玛丽乔亚"),
    )

    /** 全量规范顶层名（岛名平铺）。 */
    private val ALL_ISLANDS: List<String> = ISLAND_TOP_LEVELS.values.flatten()

    /**
     * 顶层归一：原文包含规范岛名 → "{岛名}"；包含"玛丽乔亚/九蛇"等特殊词同样命中。
     * 未命中任何规范名（含"伟大航路"类模糊域词——终审必改①：**不做域映射**，保留原文由调用侧打日志）。
     */
    fun normalizeIslandTop(locationRaw: String): String? {
        val hit = ALL_ISLANDS.firstOrNull { locationRaw.contains(it) } ?: return null
        return hit
    }

    /** locationKey 组装：{顶层规范名}·{词表命中词}；顶层未规范化 → 仅词表命中词（原文保留，调用侧日志）。 */
    private fun buildLocationKey(locationRaw: String, keywordHit: String): String {
        val top = normalizeIslandTop(locationRaw) ?: return keywordHit
        return "$top·$keywordHit"
    }

    /** 顶层是否已规范（未规范时调用侧打 location_unnormalized 日志·fail-open 不阻塞）。 */
    fun isTopLevelNormalized(locationRaw: String): Boolean = normalizeIslandTop(locationRaw) != null

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
