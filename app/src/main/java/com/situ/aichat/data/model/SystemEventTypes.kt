package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 系统事件消息（1:1 iOS `Views/Chat/SystemEventCard.swift` 的数据模型部分 + `Models/MessageContent` 的红包视角文案）。
 *
 * 系统事件以 [MessageKind.SYSTEM_EVENT_CARD] 消息（role=system）插入聊天流：
 * - **UI**（9.3b）：红包三态渲染为居中极简灰字（🧧 + title）。
 * - **LLM 历史**：PromptBuilder 用 [buildRedPacketLLMRepresentation] 第一人称视角文案代替原文，并按 [systemEventTargetIsAssistant]
 *   归属 user/assistant bucket（让「角色发红包→被领取→角色回应」形成自然对话节奏）。
 *
 * 老 3 case（relationshipChange/memoryUpdate/growthAnalysis）当前 Android 生产代码未使用，保留枚举与兜底以对齐 iOS。
 */
enum class SystemEventType(val raw: String) {
    RELATIONSHIP_CHANGE("relationship_change"),
    MEMORY_UPDATE("memory_update"),
    GROWTH_ANALYSIS("growth_analysis"),

    // 红包生命周期事件（阶段 5.5 · Sub D.3）
    /** 红包被接收方收下（user→character 被角色收，或 character→user 被用户手动拆）。 */
    RED_PACKET_ACCEPTED("red_packet_accepted"),
    /** 红包被接收方拒收（主路径：character 拒收 user 的红包）。 */
    RED_PACKET_REJECTED("red_packet_rejected"),
    /** 红包 24h 未拆自动过期（主路径：user 没拆 character 发的红包）。 */
    RED_PACKET_EXPIRED("red_packet_expired"),

    /** [zCODE] P1·第2项 B1·场景节点通知：锚点 MotionState/locationKey 变更 → 聊天流轻量系统条（⚓ 居中灰字，复用红包系统卡样式）。 */
    SCENE_UPDATE("scene_update");

    /** 是否红包类事件（分派 UI 样式 / llmRepresentation 文案，1:1 iOS `isRedPacketEvent`）。 */
    val isRedPacketEvent: Boolean
        get() = this == RED_PACKET_ACCEPTED || this == RED_PACKET_REJECTED || this == RED_PACKET_EXPIRED

    companion object {
        private val byRaw = entries.associateBy { it.raw }

        /** 从 rawValue 还原；**未知值返回 null**（1:1 iOS `SystemEventType(rawValue:)`，调用方据此兜底）。 */
        fun fromRaw(raw: String): SystemEventType? = byRaw[raw]
    }
}

/** 发送方角色（对应 [WalletOwnerType].raw / RedPacketRecord.senderType，1:1 iOS `RedPacketEventSenderRole`）。 */
enum class RedPacketEventSenderRole(val raw: String) {
    USER("user"),
    CHARACTER("character"),
}

/**
 * 系统事件数据（1:1 iOS `SystemEventData`，存 Message.content JSON）。
 * 老字段 type/eventType/title/emoji/timestamp 公用；新 nullable 字段（amount/rejectionReason/blessingText/senderRole）
 * 红包专用、老事件为 null。全 Optional 保证 JSON 向后兼容（[SystemEventJson] encodeDefaults=false 时 null 省略）。
 */
@Serializable
data class SystemEventData(
    val type: String,
    val eventType: String,
    val title: String,
    val emoji: String,
    val timestamp: String,
    // 红包专用（老事件 null）
    val amount: Int? = null,
    val rejectionReason: String? = null,
    val blessingText: String? = null,
    val senderRole: String? = null,
)

/** 系统事件 JSON 编解码（encodeDefaults=false 省略 null 的红包专用字段，对齐 iOS Codable；老事件 round-trip 不写多余字段）。 */
object SystemEventJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(data: SystemEventData): String = json.encodeToString(SystemEventData.serializer(), data)

    /** 解析（校验 "{" 开头 + type=="system_event"，1:1 iOS `parseSystemEvent`）。 */
    fun parse(content: String): SystemEventData? {
        if (!content.startsWith("{")) return null
        val data = runCatching { json.decodeFromString<SystemEventData>(content) }.getOrNull() ?: return null
        return if (data.type == "system_event") data else null
    }
}

// ── 红包系统事件 factory + 文案（纯函数，便于单测，1:1 iOS） ──────────────────

/** [zCODE] P1·第2项 B1：场景节点通知条（⚓ 居中灰字轻量系统条；title =「场景更新：{事件}·{位置}」）。 */
fun makeSceneUpdateEventData(eventName: String, locationRaw: String, timestampMillis: Long): SystemEventData = SystemEventData(
    type = "system_event",
    eventType = SystemEventType.SCENE_UPDATE.raw,
    title = buildString {
        append("场景更新")
        val detail = listOf(eventName.trim(), locationRaw.trim()).filter { it.isNotEmpty() }.joinToString("·")
        if (detail.isNotEmpty()) append("：").append(detail)
    }.take(40), // 与注入防御同口径：模型原文截断
    emoji = "⚓",
    timestamp = ISO_TS.format(Instant.ofEpochMilli(timestampMillis)),
)

private val ISO_TS: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * 构造红包类系统事件 [SystemEventData]（1:1 iOS `makeRedPacketSystemEventData`）。
 * - eventType 须为红包三 case 之一（调用方保证）；emoji 统一 🧧。
 * - [amount] 金额快照（resolved 后可暴露）；[rejectionReason] 仅 rejected 传；[blessingText] 可 null；[senderRole] 必填（决定视角）。
 * - [characterName] 用于 title 主宾语；[timestampMillis] 形成 ISO8601 时间戳串。
 */
fun makeRedPacketSystemEventData(
    eventType: SystemEventType,
    amount: Int,
    blessingText: String?,
    rejectionReason: String?,
    senderRole: RedPacketEventSenderRole,
    characterName: String,
    timestampMillis: Long,
): SystemEventData = SystemEventData(
    type = "system_event",
    eventType = eventType.raw,
    title = buildRedPacketEventTitle(eventType, senderRole, characterName),
    emoji = "🧧",
    timestamp = ISO_TS.format(Instant.ofEpochMilli(timestampMillis)),
    amount = amount,
    rejectionReason = rejectionReason,
    blessingText = blessingText,
    senderRole = senderRole.raw,
)

/**
 * 红包事件 title 文案（UI 用户视角，1:1 iOS `buildRedPacketEventTitle`）。name 空 → "对方"；非红包 case → ""。
 */
fun buildRedPacketEventTitle(
    eventType: SystemEventType,
    senderRole: RedPacketEventSenderRole,
    characterName: String,
): String {
    val name = characterName.ifEmpty { "对方" }
    val senderIsUser = senderRole == RedPacketEventSenderRole.USER
    return when (eventType) {
        SystemEventType.RED_PACKET_ACCEPTED ->
            if (senderIsUser) "${name}收下了你的红包" else "你收下了${name}的红包"
        SystemEventType.RED_PACKET_REJECTED ->
            if (senderIsUser) "${name}拒收了你的红包" else "你拒收了${name}的红包"
        SystemEventType.RED_PACKET_EXPIRED ->
            if (senderIsUser) "发给${name}的红包 24 小时未拆,已退回" else "${name}发的红包 24 小时未拆,已退回"
        else -> ""
    }
}

/**
 * 红包系统事件 → LLM **角色第一人称视角**文案（1:1 iOS `buildRedPacketLLMRepresentation`）。
 * - 角色称自己「你」、称对方用 [userName]（未传默认「用户」·人称=你+用户名·图纸一 R1 承接）。senderRole 缺失 → 按「用户发」兜底（语义最保守）。
 * - 三态都带精确金额（resolved 后可暴露）；祝福非空带（80 字截断 + …）；仅 sender=user+rejected 带「你的理由」（30 字截断，无 …）。
 * - 非红包 eventType 退化为 `[系统记录：emoji+title]` 兜底。
 */
fun buildRedPacketLLMRepresentation(data: SystemEventData, typed: SystemEventType, userName: String = "用户"): String {
    val senderIsUser = (data.senderRole ?: RedPacketEventSenderRole.USER.raw) == RedPacketEventSenderRole.USER.raw

    val actionClause = when (typed) {
        SystemEventType.RED_PACKET_ACCEPTED -> if (senderIsUser) "你收下了${userName}的红包" else "${userName}收下了你的红包"
        SystemEventType.RED_PACKET_REJECTED -> if (senderIsUser) "你拒收了${userName}的红包" else "${userName}拒收了你的红包"
        SystemEventType.RED_PACKET_EXPIRED ->
            if (senderIsUser) "${userName}发给你的红包 24 小时未拆,自动退回" else "你发给${userName}的红包 24 小时未被拆开,自动退回"
        else -> return "[系统记录：${data.emoji}${data.title}]"
    }

    val parts = mutableListOf("系统记录：$actionClause")

    val amount = data.amount
    if (amount != null && amount > 0) parts.add("金额=$amount 金币")

    data.blessingText?.trim()?.takeIf { it.isNotEmpty() }?.let {
        val capped = if (it.length > 80) it.take(80) + "…" else it
        parts.add("祝福=「$capped」")
    }

    if (typed == SystemEventType.RED_PACKET_REJECTED && senderIsUser) {
        data.rejectionReason?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val capped = if (it.length > 30) it.take(30) else it
            parts.add("你的理由=「$capped」")
        }
    }

    return "[" + parts.joinToString(" | ") + "]"
}

/**
 * 系统事件消息归属（1:1 iOS PromptBuilder `targetRoleForSystemEvent`）：按「动作执行者」归属，让文案主语和 role 对齐。
 * 返回 true = 归 assistant（角色）bucket，false = 归 user bucket。
 * - accepted/rejected：收/拒由**接收方**做 → user 发→角色做→assistant；character 发→用户做→user（= senderIsUser）。
 * - expired：无人做动作，归**发起方** → user 发→user；character 发→assistant（= !senderIsUser）。
 * - 非红包 case（老 relationshipChange 等 / 解析失败）→ 兜底 user（false），与 iOS 老行为一致。senderRole 缺失按「用户发」。
 */
fun systemEventTargetIsAssistant(data: SystemEventData): Boolean {
    val typed = SystemEventType.fromRaw(data.eventType) ?: return false
    if (!typed.isRedPacketEvent) return false
    val senderIsUser = (data.senderRole ?: RedPacketEventSenderRole.USER.raw) == RedPacketEventSenderRole.USER.raw
    return when (typed) {
        SystemEventType.RED_PACKET_ACCEPTED, SystemEventType.RED_PACKET_REJECTED -> senderIsUser
        SystemEventType.RED_PACKET_EXPIRED -> !senderIsUser
        else -> false
    }
}
