package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * [zCODE] P1·第2项 锚点中央登记（评审点1·存储层）：剧情锚点快照，append-only 历史。
 *
 * 语义：一行 = 某角色在某时刻的「当前事件 + 位置」快照。写入方仅 [com.situ.aichat.data.repository.StoryStateRepository]
 * （对话锚点块落库 / 无块兜底延续 / 导演[P4] / 手动[P3] 四通道）。「当前锚点」= 每角色 max(capturedAt) 一行。
 *
 * - **时效保真**（评审拍板）：兜底延续（source=dialog_carryover）行**原样携带**上一快照的 [effectiveAt]——
 *   carryover 物理续链但绝不刷新时效；下游按读取时 `now - effectiveAt` 动态分级（见 AnchorVocabulary.FreshnessLevel），
 *   三天前的位置永远不会被当实时。
 * - **幂等**（评审附加条件1·硬性）：唯一索引 (relatedMessageUUID, sourceRaw)——同一回合（同一收尾消息）+ 同通道
 *   重试/多路径收尾只落一行；插入用 OnConflictStrategy.IGNORE。
 * - **量级账**（评审附加条件3·摆明面）：重度用户（50 回合/日/角色）含 carryover 每回合 1 行 ≈ 1.8 万行/角色/年，
 *   行宽 ~300B ≈ 5.5MB/角色/年；10 卡全库 ≈ 55MB/年。SQLite 该量级零负担；历史即 P3 管理台时间线原料，不裁剪
 *   （评审裁决3：裁剪=自毁资产）。若未来确需裁剪，按 source 优先砍 dialog_carryover 行（信息量最低）。
 */
@Entity(
    tableName = "story_anchor_snapshots",
    indices = [
        Index(value = ["characterUuid"]),
        Index(value = ["relatedMessageUUID", "sourceRaw"], unique = true),
    ],
)
data class StoryAnchorSnapshotEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    /** 归属角色卡——各卡独立位置（白团靠岸 ≠ 佩罗娜靠岸）。 */
    val characterUuid: String,
    val conversationUuid: String = "",
    /** 当前事件（规范化后人话，如「甲板守夜」）。空 = 仅位置无事件。 */
    val eventName: String = "",
    /** 模型原文地点（保真留档，供 P3 管理台展示与修正比对）。 */
    val locationRaw: String = "",
    /** 归一化地点键（AnchorVocabulary 产出）。null = 词表不识，下游按「位置不明」处理，不硬猜。 */
    val locationKey: String? = null,
    /** 移动状态枚举 raw（sailing/docked/ashore/indoors/in_transit/unknown）——节点命名规范化的判定源。 */
    val motionStateRaw: String = "unknown",
    /** 写入通道：dialog_block | dialog_carryover | director | manual。 */
    val sourceRaw: String = "dialog_block",
    /** 锚点描述成立的时刻（carryover 原样携带，绝不后移）。 */
    val effectiveAt: Long = System.currentTimeMillis(),
    /** 落库时刻。 */
    val capturedAt: Long = System.currentTimeMillis(),
    /** 幂等键的来源消息（本回合最后一条落库消息）。manual/director 行填事件 uuid。 */
    val relatedMessageUUID: String = "",

    /** [zCODE] P1·第3项：本快照时的船团键（空 = 无团；world_sync 行 = 广播源团键）。 */
    val fleetKey: String = "",

    /**
     * [zCODE] P1·第3项：在场名单 JSON（规范式锚点块提取：`[{"name":…,"present":true/false,"location":…}]`）。
     * 仅规范式提取，系统不做会话参与人推断；**历史行空串不回填**（手动修正兜底留 P3）。
     */
    val presentListJson: String = "",
)

/** 快照写入通道（raw 值即库契约，重命名断历史）。 */
enum class AnchorSource(val raw: String) {
    DIALOG_BLOCK("dialog_block"),
    DIALOG_CARRYOVER("dialog_carryover"),
    DIRECTOR("director"),
    MANUAL("manual"),

    /** [zCODE] P1·第3项：船团广播行（**不触发再广播**——防环白名单外；广播行即纯基线，无个体前缀）。 */
    WORLD_SYNC("world_sync");

    companion object {
        fun fromRaw(raw: String): AnchorSource = entries.firstOrNull { it.raw == raw } ?: DIALOG_BLOCK
    }
}
