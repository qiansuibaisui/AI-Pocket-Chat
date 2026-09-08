package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 未来约定见面「确认卡」消息快照（1:1 iOS `FutureMeetingProposalData`）。插进聊天的那张卡的自包含数据。
 *
 * 与真理源 [com.situ.aichat.data.local.entity.MeetingAppointmentEntity] 的分工：本快照是消息 content（UI 渲染起点·
 * 通过 [appointmentUuid] 关联）；约定状态 / 时间真相以 Entity 为准（气泡实时观察其 status 刷新按钮态）。
 * 属结构化卡（[MessageKind.isStructuredCard]）：**绝不把原文 JSON 喂 LLM / 复制给用户**，LLM 侧走 [llmRepresentation]。
 */
@Serializable
data class FutureMeetingProposalData(
    val type: String = TYPE,
    val appointmentUuid: String,
    /** 时间人话（[com.situ.aichat.meeting.MeetingDisplayFormatter.whenDisplay] 产）；可空。 */
    val whenDisplay: String? = null,
    val location: String? = null,
    val activity: String? = null,
    val invitation: String? = null,
    /** ≤12 字给用户看的隐晦暗示（不剧透）。 */
    val tensionHint: String? = null,
    /** null = 待确认（答应/换时间/先不约 三按钮）；[RESPONDED_ACCEPTED] = 已约定回执；[RESPONDED_DECLINED] = 已婉拒回执；[RESPONDED_SUPERSEDED] = 系统作废回执（[zCODE] P1 矛盾守卫，重复条目收回执防按钮悬空）。 */
    val responded: String? = null,
) {
    /** 给 LLM 的脱敏表示（结构化卡绝不喂原文 JSON；收口走此处）。[userName]=用户名（默认「用户」·图纸一 R1 承接·你=角色+用户名）。 */
    fun llmRepresentation(userName: String = "用户"): String {
        val parts = listOfNotNull(
            whenDisplay?.takeIf { it.isNotBlank() }?.let { "时间=$it" },
            location?.takeIf { it.isNotBlank() }?.let { "地点=$it" },
            activity?.takeIf { it.isNotBlank() }?.let { "活动=$it" },
        )
        val detail = if (parts.isEmpty()) "" else " | " + parts.joinToString(" | ")
        return "[系统记录：向${userName}提出了未来见面的约定$detail]"
    }

    companion object {
        const val TYPE = "future_meeting_proposal"
        const val RESPONDED_ACCEPTED = "accepted"
        const val RESPONDED_DECLINED = "declined"

        /** [zCODE] P1：被「保留最新」矛盾守卫作废的重复卡回执（UI 按已作废收起按钮）。 */
        const val RESPONDED_SUPERSEDED = "superseded"
    }
}

/** [FutureMeetingProposalData] 的 JSON 编解码（仿 [RedPacketData] 的 RedPacketJson）。 */
object FutureMeetingProposalJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(data: FutureMeetingProposalData): String =
        json.encodeToString(FutureMeetingProposalData.serializer(), data)

    /** 解析；非本类型 / 损坏 → null。 */
    fun parse(content: String): FutureMeetingProposalData? {
        val data = runCatching { json.decodeFromString(FutureMeetingProposalData.serializer(), content) }.getOrNull()
            ?: return null
        return if (data.type == FutureMeetingProposalData.TYPE) data else null
    }
}
