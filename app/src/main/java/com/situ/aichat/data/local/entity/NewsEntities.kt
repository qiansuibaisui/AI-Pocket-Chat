package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * [zCODE] P3·摩根斯新闻管道：新闻事件条目（不可变——谁、在哪、干了什么）。
 *
 * 摩根斯做功能不做卡（已裁决）：本条目是发布者皮套的数据载体，不建角色卡、不参与互动。
 * 触发源（MVP）：线下见面结束（OfflineSummaryRetryCoordinator 成功钩子）；
 * 接口预留：`NewsPipeline.publish(draft)` 单一入口——P4 导演/P5 群聊未来经此发布（本批不写适配代码）。
 */
@Entity(tableName = "news_events", indices = [Index(value = ["characterUuid"]), Index(value = ["occurredAt"])])
data class NewsEventEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    /** 事件主角（谁的新闻——多主角逗号分隔）。 */
    val characterUuid: String,
    /** 主角名冗余快照（防删卡后不可读）。 */
    val characterName: String = "",
    /** 地点（locationKey 顶层规范岛名/船名——八大域同源）。 */
    val locationKey: String = "",
    /** 事件人话摘要（≤100 字·"与千岁在香波地码头见面"）。 */
    val eventSummary: String = "",
    /** 严重度（minor/normal/major——影响传播范围与细节保留）。 */
    val severityRaw: String = "normal",
    /** 事件发生时刻。 */
    val occurredAt: Long = System.currentTimeMillis(),
    /** 触发源（meeting_end / anchor_event / director / group_chat）。 */
    val sourceRaw: String = "meeting_end",
    /** 源引用 uuid（sessionId 等——幂等键基础）。 */
    val sourceRefUuid: String = "",
)

/**
 * 新闻触达记录（认知矩阵"转述层"行——摩根斯管道产出恒为 ② 层）。
 * P6 管理台未建成，本批只落库不建界面；字段兼容第 3 项蓝图三代样本（亲历/转述/单向知晓）。
 */
@Entity(
    tableName = "news_deliveries",
    indices = [Index(value = ["targetCharacterUuid"]), Index(value = ["newsEventUuid", "targetCharacterUuid"], unique = true)],
)
data class NewsDeliveryEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    /** 新闻条目 uuid。 */
    val newsEventUuid: String,
    /** 触达角色。 */
    val targetCharacterUuid: String,
    /** 知晓层级（恒 "reported"=② 转述层——本管道不产亲历/单向）。 */
    val knowledgeLayer: String = "reported",
    /** 来源标记（必带·恒 "morgans"）。 */
    val sourceTag: String = "morgans",
    /** 触达时刻（含传播延迟后的实际触达时间）。 */
    val deliveredAt: Long = System.currentTimeMillis(),
    /** 细节级别（2=详版 1=概述 0=模糊——衰减规则产物）。 */
    val detailLevel: Int = 1,
    /** 转述措辞（已按衰减级别格式化——"据报道，…"）。 */
    val narrativeText: String = "",
)

/** 新闻事件严重度。 */
enum class NewsSeverity(val raw: String) {
    MINOR("minor"), NORMAL("normal"), MAJOR("major");

    companion object {
        fun fromRaw(raw: String): NewsSeverity = entries.firstOrNull { it.raw == raw } ?: NORMAL
    }
}

/** 新闻事件触发源。 */
enum class NewsSource(val raw: String) {
    MEETING_END("meeting_end"),
    ANCHOR_EVENT("anchor_event"),
    DIRECTOR("director"),     // P4 预留
    GROUP_CHAT("group_chat"); // P5 预留

    companion object {
        fun fromRaw(raw: String): NewsSource = entries.firstOrNull { it.raw == raw } ?: MEETING_END
    }
}
