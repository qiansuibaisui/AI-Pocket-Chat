package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.AnchorSource
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import com.situ.aichat.data.local.entity.StoryEventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [zCODE] P1·第2项：备份段单测——导出/导入映射 round-trip + 老备份（18 段）兼容语义
 * （评审附加条件4：段缺失 → 两字段 null → 恢复留空从零建档，降级不报错）。
 */
class StoryStateBackupTest {

    @Test fun anchor_roundtrip_fields_preserved() {
        val entity = StoryAnchorSnapshotEntity(
            uuid = "u1", characterUuid = "c1", conversationUuid = "v1",
            eventName = "守夜", locationRaw = "甲板", locationKey = "甲板",
            motionStateRaw = "sailing", sourceRaw = AnchorSource.DIALOG_CARRYOVER.raw,
            effectiveAt = 111L, capturedAt = 222L, relatedMessageUUID = "m1",
        )
        val restored = entity.toExport().toEntity()
        assertEquals(entity, restored)
    }

    @Test fun ledger_roundtrip_fields_preserved() {
        val entity = StoryEventLedgerEntity(
            uuid = "u2", characterUuid = "c1", conversationUuid = "v1",
            eventKey = "market-dinner", description = "与千岁在集市吃饭，已送出芒果",
            completedAt = 333L, sourceRaw = StoryEventSource.OFFLINE_MEETING.raw, relatedMessageUUID = "m2",
        )
        assertEquals(entity, entity.toExport().toEntity())
    }

    @Test fun old_backup_null_segments_mean_empty_rebuild() {
        // 老备份（≤18 段）：BackupPackage 两个新字段 null —— kotlinx 反序列化缺字段 → 默认 null（BackupImporter 的
        // Json ignoreUnknownKeys/缺省默认值路径），恢复函数收 null → 不写任何行 = 留空从零建档，不抛。
        // 这里直接锁 restoreStoryState(null, null) 的行为契约。
        val dao = io.mockk.mockk<com.situ.aichat.data.local.dao.StoryStateDao>(relaxed = true)
        kotlinx.coroutines.runBlocking {
            restoreStoryState(dao, anchors = null, events = null, existingCharacterUuids = setOf("c1"))
        }
        io.mockk.coVerify(exactly = 0) { dao.insertOrReplaceSnapshots(any()) }
        io.mockk.coVerify(exactly = 0) { dao.insertOrReplaceLedger(any()) }
    }

    @Test fun ghost_character_rows_skipped_on_restore() {
        val dao = io.mockk.mockk<com.situ.aichat.data.local.dao.StoryStateDao>(relaxed = true)
        val anchor = StoryAnchorSnapshotEntity(uuid = "u1", characterUuid = "ghost", relatedMessageUUID = "m1")
        val event = StoryEventLedgerEntity(uuid = "u2", characterUuid = "ghost", eventKey = "k")
        kotlinx.coroutines.runBlocking {
            restoreStoryState(
                dao,
                anchors = listOf(anchor.toExport()),
                events = listOf(event.toExport()),
                existingCharacterUuids = setOf("alive"), // ghost 角色已删 → 行跳过
            )
        }
        io.mockk.coVerify(exactly = 0) { dao.insertOrReplaceSnapshots(any()) }
        io.mockk.coVerify(exactly = 0) { dao.insertOrReplaceLedger(any()) }
        assertNull(null) // 显式无异常完成
    }
}
