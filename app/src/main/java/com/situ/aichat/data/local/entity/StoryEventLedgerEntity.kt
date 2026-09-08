package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * [zCODE] P1·第2项 锚点中央登记（评审点1·存储层）：已完成事件/已执行流程登记（append-only 账本）。
 *
 * 治「剧本重演」的判定源：模型无法区分已做/未做 → 下游注入时声明「以下流程已执行完毕，不得重新执行」
 * （注入接线在评审点2·读取处1）。登记来源：线下见面收尾（谁做了什么/给了什么/达成什么约定）、
 * 对话内明确确认、导演[P4]、手动[P3]。
 *
 * - **幂等去重**：唯一索引 (characterUuid, eventKey)——同键重复登记不堆行（IGNORE）。
 * - **保留策略**（评审裁决3）：永久。千行/角色/年级对 SQLite 零负担，历史即 P3 时间线原料。
 */
@Entity(
    tableName = "story_event_ledger",
    indices = [
        Index(value = ["characterUuid"]),
        Index(value = ["characterUuid", "eventKey"], unique = true),
    ],
)
data class StoryEventLedgerEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val characterUuid: String,
    val conversationUuid: String = "",
    /** 规范化事件键（同键=同一事件，幂等去重键的一半；建议「动词+对象」短语小写化）。 */
    val eventKey: String,
    /** 人话描述：「与千岁在集市吃饭，已送出芒果」。 */
    val description: String = "",
    val completedAt: Long = System.currentTimeMillis(),
    /** 登记来源：offline_meeting | dialog_ack | director | manual。 */
    val sourceRaw: String = "dialog_ack",
    val relatedMessageUUID: String? = null,
)

/** 账本登记来源（raw 值即库契约）。 */
enum class StoryEventSource(val raw: String) {
    OFFLINE_MEETING("offline_meeting"),
    DIALOG_ACK("dialog_ack"),
    DIRECTOR("director"),
    MANUAL("manual");

    companion object {
        fun fromRaw(raw: String): StoryEventSource = entries.firstOrNull { it.raw == raw } ?: DIALOG_ACK
    }
}
