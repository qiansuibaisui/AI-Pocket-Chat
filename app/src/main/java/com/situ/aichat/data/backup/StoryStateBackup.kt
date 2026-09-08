package com.situ.aichat.data.backup

import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import kotlinx.serialization.Serializable

/**
 * [zCODE] P1·第2项 锚点中央登记备份段（评审点1·全局段 19/20）：快照 + 账本整存整取。
 *
 * **兼容语义（评审附加条件4）**：老备份（18 段·无 storyAnchors/storyEvents 字段）导入新包 → 两字段 null →
 * 两表留空、锚点从零建档（下回合对话锚点块/兜底即开始积累）——**降级不报错**；新备份（20 段）导入老包 →
 * 老导入器按段名取件、未知段不读，安全。恢复按幽灵 characterUuid 行跳过（角色已删不复活），
 * uuid REPLACE + 唯一索引双兜底（同 (relatedMessageUUID, sourceRaw) / (characterUuid, eventKey) 幂等）。
 */
@Serializable
data class StoryAnchorBackupData(
    val uuid: String,
    val characterUuid: String,
    val conversationUuid: String = "",
    val eventName: String = "",
    val locationRaw: String = "",
    val locationKey: String? = null,
    val motionStateRaw: String = "unknown",
    val sourceRaw: String = "dialog_block",
    val effectiveAt: Long = 0L,
    val capturedAt: Long = 0L,
    val relatedMessageUUID: String = "",
)

@Serializable
data class StoryEventBackupData(
    val uuid: String,
    val characterUuid: String,
    val conversationUuid: String = "",
    val eventKey: String,
    val description: String = "",
    val completedAt: Long = 0L,
    val sourceRaw: String = "dialog_ack",
    val relatedMessageUUID: String? = null,
)

internal fun StoryAnchorSnapshotEntity.toExport() = StoryAnchorBackupData(
    uuid = uuid, characterUuid = characterUuid, conversationUuid = conversationUuid,
    eventName = eventName, locationRaw = locationRaw, locationKey = locationKey,
    motionStateRaw = motionStateRaw, sourceRaw = sourceRaw,
    effectiveAt = effectiveAt, capturedAt = capturedAt, relatedMessageUUID = relatedMessageUUID,
)

internal fun StoryAnchorBackupData.toEntity() = StoryAnchorSnapshotEntity(
    uuid = uuid, characterUuid = characterUuid, conversationUuid = conversationUuid,
    eventName = eventName, locationRaw = locationRaw, locationKey = locationKey,
    motionStateRaw = motionStateRaw, sourceRaw = sourceRaw,
    effectiveAt = effectiveAt, capturedAt = capturedAt, relatedMessageUUID = relatedMessageUUID,
)

internal fun StoryEventLedgerEntity.toExport() = StoryEventBackupData(
    uuid = uuid, characterUuid = characterUuid, conversationUuid = conversationUuid,
    eventKey = eventKey, description = description, completedAt = completedAt,
    sourceRaw = sourceRaw, relatedMessageUUID = relatedMessageUUID,
)

internal fun StoryEventBackupData.toEntity() = StoryEventLedgerEntity(
    uuid = uuid, characterUuid = characterUuid, conversationUuid = conversationUuid,
    eventKey = eventKey, description = description, completedAt = completedAt,
    sourceRaw = sourceRaw, relatedMessageUUID = relatedMessageUUID,
)

/** 收集（Exporter·老备份兼容：全量升序，空 → null 段省略）。 */
internal suspend fun collectStoryState(dao: StoryStateDao): Pair<List<StoryAnchorBackupData>?, List<StoryEventBackupData>?> =
    dao.allSnapshots().map { it.toExport() }.ifEmpty { null } to dao.allLedger().map { it.toExport() }.ifEmpty { null }

/** 恢复（Importer 事务内·幽灵 characterUuid 行跳过·uuid REPLACE 幂等·老备份段缺失 = 留空从零建档）。 */
internal suspend fun restoreStoryState(
    dao: StoryStateDao,
    anchors: List<StoryAnchorBackupData>?,
    events: List<StoryEventBackupData>?,
    existingCharacterUuids: Set<String>,
) {
    anchors?.filter { it.characterUuid in existingCharacterUuids }?.takeIf { it.isNotEmpty() }?.let { dao.insertOrReplaceSnapshots(it.map { d -> d.toEntity() }) }
    events?.filter { it.characterUuid in existingCharacterUuids }?.takeIf { it.isNotEmpty() }?.let { dao.insertOrReplaceLedger(it.map { d -> d.toEntity() }) }
}
