package com.situ.aichat.director

/**
 * [zCODE] B9 规则档频率参数位（终审五数·2026-10-01 拍板照准：6h/10%/日2/72h/报30%）。
 *
 * 本批=常量形态（管道级参数位）；C10 频率控制台收编时改为读设置存储——**只改本对象的取值来源**，
 * 调用面（[DirectorRulesScanService] 与 Worker 周期）零改。
 */
object DirectorRulesConfig {

    /** 扫描周期：6h——对齐锚点 FRESH 上限（位置硬约束时效=扫描窗，语义自洽）。 */
    const val SCAN_INTERVAL_MS: Long = 6L * 3600_000

    /** 每合格角色对每扫描的触发概率：10%（期望≈每对每日 0.4 次——远方事件该稀）。 */
    const val TRIGGER_PROBABILITY: Double = 0.10

    /** 全库每日上限：2 条（轻量事件不刷屏；跨对分摊）。 */
    const val DAILY_CAP: Int = 2

    /** 单对冷却：72h 内同对不再触发。 */
    const val PAIR_COOLDOWN_MS: Long = 72L * 3600_000

    /** 触发后择机摩根斯报道概率：30%。B8 手动档不报（用户亲历无需新闻）——写死在 B8 侧，非本对象职责。 */
    const val NEWS_REPORT_PROBABILITY: Double = 0.30

    /**
     * 用户位置代理时效上限：24h（AGING 界）。最近一次线下见面超出此时长 → 代理失效 → 本次扫描整体放弃
     * （fail-closed：断言不了"{{user}} 不在场"就不产远方事件——认知红线不许赌）。
     */
    const val PROXY_MAX_AGE_MS: Long = 24L * 3600_000

    /** [拍板C] 用户船团兜底覆盖：非空时代理位置=该团成员最新锚点（跳过自动代理链）。空=自动代理（最近一次 OFFLINE_MEETING 记账行 → 该角色当前锚点）。 */
    const val USER_FLEET_KEY_OVERRIDE: String = ""
}
