package com.situ.aichat.data.model

/**
 * 「未来约定见面」相关枚举（1:1 iOS `Models/MeetingAppointment.swift` 内嵌枚举）。
 *
 * 全部以 raw 字符串持久化到 [com.situ.aichat.data.local.entity.MeetingAppointmentEntity] 对应列
 * （与红包 [RedPacketStatus] / 礼物同约定）。各 `raw` 值是数据库与备份 JSON 的稳定契约——
 * **重命名会断历史数据**，由 MeetingAppointmentTypesTest 的 raw 断言锁定（改 raw 即断测试，而非静默失效）。
 */

/** 约定生命周期状态。proposed/confirmed = 进行中；honored/missed/cancelled = 终态（不可再流转）。 */
enum class MeetingStatus(val raw: String) {
    /** 已识别、已提出，但用户尚未点头确认（确认卡「待确认」态）。 */
    PROPOSED("proposed"),

    /** 已确认、已排到点提醒——等待期的正式约定。 */
    CONFIRMED("confirmed"),

    /** 已赴约（到点进入线下见面）。终态。 */
    HONORED("honored"),

    /** 爽约（过了宽限期仍未赴约）。终态。 */
    MISSED("missed"),

    /** 已取消。终态。 */
    CANCELLED("cancelled"),

    /**
     * [zCODE] P1·已作废（终态）：短期重复生成的同类邀约被「保留最新」守卫作废（矛盾态防并存）。
     * 与 cancelled 的语义差：cancelled = 用户显式婉拒；superseded = 系统作废的重复条目（用户从未响应过它）。
     */
    SUPERSEDED("superseded");

    /**
     * 是否进行中——可被改期 / 取消 / 赴约命中，也是查重比对的范围（终态三者排除在外）。
     * 「proposed 还在商量、confirmed 已敲定」都算进行中；honored/missed/cancelled 已盖棺。
     */
    val isActive: Boolean
        get() = this == PROPOSED || this == CONFIRMED

    companion object {
        private val byRaw = entries.associateBy { it.raw }

        /** 从 raw 还原；未知值保守回退 [PROPOSED]。 */
        fun fromRaw(raw: String): MeetingStatus = byRaw[raw] ?: PROPOSED
    }
}

/** 谁发起的约定（活人感关键：角色也会主动约未来见面）。 */
enum class MeetingProposedBy(val raw: String) {
    CHARACTER("character"),
    USER("user");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): MeetingProposedBy = byRaw[raw] ?: CHARACTER
    }
}

/** 约定来源（溯源调参用，不影响行为）。 */
enum class MeetingSource(val raw: String) {
    /** 后台抽取扫描识别（骨干路）。 */
    EXTRACTION("extraction"),

    /** LLM 工具调用（当场举手快路）。 */
    TOOL("tool"),

    /** 弱模型文本兜底解析。 */
    FALLBACK("fallback"),

    /** 用户手动发起。 */
    MANUAL("manual");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): MeetingSource = byRaw[raw] ?: EXTRACTION
    }
}

/** 见面时刻的精度——决定到点提醒与宽限期怎么处理。 */
enum class MeetingTimeGranularity(val raw: String) {
    /** 精确到点（如「周六 15:00」）。宽限期 = 过点 3h。 */
    EXACT("exact"),

    /** 只到某天（补默认时段 19 点）。宽限期 = 到那天结束（次日 0 点）。 */
    DAY_ONLY("dayOnly"),

    /** 模糊（认不出，给占位时间，靠确认卡让用户敲定）。宽限期同 dayOnly。 */
    VAGUE("vague");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): MeetingTimeGranularity = byRaw[raw] ?: EXACT
    }
}
