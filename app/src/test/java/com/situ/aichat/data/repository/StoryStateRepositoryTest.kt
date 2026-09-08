package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.AnchorSource
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.prompt.AnchorBlockParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P1·第2项：StoryStateRepository 单测——锚点落库幂等（评审附加条件1·硬性）、
 * 兜底延续的时效保真（effectiveAt 原样携带）、补齐提示仅 stale 时触发（裁决2）。
 */
class StoryStateRepositoryTest {

    private val dao = mockk<StoryStateDao>(relaxed = true)
    private val repo = StoryStateRepository(dao)

    private fun previous(effectiveAt: Long, capturedAt: Long) = StoryAnchorSnapshotEntity(
        characterUuid = "char-1", conversationUuid = "conv-1",
        eventName = "守夜", locationRaw = "甲板", locationKey = "甲板", motionStateRaw = "sailing",
        sourceRaw = AnchorSource.DIALOG_BLOCK.raw, effectiveAt = effectiveAt, capturedAt = capturedAt,
        relatedMessageUUID = "msg-old",
    )

    @Test fun anchor_block_persists_normalized() = runTest {
        coEvery { dao.snapshotExists(any(), any()) } returns false
        coEvery { dao.insertSnapshot(any()) } returns 1L
        val anchor = AnchorBlockParser.AnchorBlock(locationRaw = "甲板上", eventName = "守夜")

        val saved = repo.recordTurnAnchor("char-1", "conv-1", anchor, "msg-1", nowMillis = 5_000L)

        assertNotNull(saved)
        val slot = slot<StoryAnchorSnapshotEntity>()
        coVerify { dao.insertSnapshot(capture(slot)) }
        assertEquals("msg-1", slot.captured.relatedMessageUUID)
        assertEquals("dialog_block", slot.captured.sourceRaw)
        assertEquals("sailing", slot.captured.motionStateRaw)
        assertEquals("甲板", slot.captured.locationKey)
        assertEquals(5_000L, slot.captured.effectiveAt) // 模型刚输出 = 本轮成立
    }

    @Test fun idempotent_same_turn_same_channel_skips() = runTest {
        // 附加条件1：同一回合触发两次收尾 → 第二次 exists 命中 → 零写入
        coEvery { dao.snapshotExists("msg-1", "dialog_block") } returns false
        coEvery { dao.snapshotExists("msg-1", "dialog_carryover") } returns true
        coEvery { dao.insertSnapshot(any()) } returns 1L
        coEvery { dao.latestSnapshotFor("char-1") } returns previous(effectiveAt = 1_000L, capturedAt = 1_000L) // 真实体（relaxed child mock 的 copy 行为不定，不用于断言）

        val first = repo.recordTurnAnchor("char-1", "conv-1", AnchorBlockParser.AnchorBlock("甲板"), "msg-1")
        assertNotNull(first)
        // 同回合重试：锚点缺失路径（carryover）遇幂等命中 → null
        val second = repo.recordTurnAnchor("char-1", "conv-1", null, "msg-1")
        assertNull(second)
        coVerify(exactly = 1) { dao.insertSnapshot(any()) }
    }

    @Test fun carryover_preserves_effective_at() = runTest {
        // 时效保真：上一快照 effectiveAt=1_000（三天内旧值），兜底延续行原样携带、仅 capturedAt 前移
        coEvery { dao.snapshotExists(any(), any()) } returns false
        coEvery { dao.insertSnapshot(any()) } returns 1L
        coEvery { dao.latestSnapshotFor("char-1") } returns previous(effectiveAt = 1_000L, capturedAt = 1_000L)

        val carried = repo.recordTurnAnchor("char-1", "conv-1", anchor = null, turnEndMessageUuid = "msg-9", nowMillis = 9_000L)

        assertNotNull(carried)
        val slot = slot<StoryAnchorSnapshotEntity>()
        coVerify { dao.insertSnapshot(capture(slot)) }
        assertEquals("dialog_carryover", slot.captured.sourceRaw)
        assertEquals(1_000L, slot.captured.effectiveAt) // 绝不刷新
        assertEquals(9_000L, slot.captured.capturedAt)  // 物理续链
        assertEquals("msg-9", slot.captured.relatedMessageUUID)
        // 内容原样延续
        assertEquals("甲板", slot.captured.locationRaw)
        assertEquals("sailing", slot.captured.motionStateRaw)
    }

    @Test fun carryover_without_previous_is_noop() = runTest {
        coEvery { dao.latestSnapshotFor("char-1") } returns null
        coEvery { dao.snapshotExists(any(), any()) } returns false
        assertNull(repo.recordTurnAnchor("char-1", "conv-1", null, "msg-1"))
        coVerify(exactly = 0) { dao.insertSnapshot(any()) } // 从未建档 → 无可延续
    }

    @Test fun unique_index_backstop_returns_null() = runTest {
        // 并发写：exists 预检漏过但 insert 返回 -1（IGNORE 命中唯一索引）→ null，不炸
        coEvery { dao.snapshotExists(any(), any()) } returns false
        coEvery { dao.insertSnapshot(any()) } returns -1L
        assertNull(repo.recordTurnAnchor("char-1", "conv-1", AnchorBlockParser.AnchorBlock("甲板"), "msg-1"))
    }

    @Test fun nudge_only_when_stale() = runTest {
        val now = 100L * 3600_000
        // fresh（1h 前）→ 不提
        coEvery { dao.latestSnapshotFor("char-1") } returns previous(effectiveAt = now - 3600_000, capturedAt = now - 3600_000)
        assertNull(repo.anchorNudgeText("char-1", now))
        // aging（12h 前）→ 不提（裁决2：仅 stale 时提）
        coEvery { dao.latestSnapshotFor("char-1") } returns previous(effectiveAt = now - 12L * 3600_000, capturedAt = now - 12L * 3600_000)
        assertNull(repo.anchorNudgeText("char-1", now))
        // stale（三天前）→ 提，含小时数与位置
        coEvery { dao.latestSnapshotFor("char-1") } returns previous(effectiveAt = now - 72L * 3600_000, capturedAt = now - 72L * 3600_000)
        val nudge = repo.anchorNudgeText("char-1", now)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("72"))
        assertTrue(nudge.contains("甲板"))
        // 从未建档 → 不提
        coEvery { dao.latestSnapshotFor("char-2") } returns null
        assertNull(repo.anchorNudgeText("char-2", now))
    }

    @Test fun fresh_anchor_respects_max_age() = runTest {
        val now = 100L * 3600_000
        coEvery { dao.latestSnapshotFor("char-1") } returns previous(effectiveAt = now - 3600_000, capturedAt = now - 3600_000)
        assertNotNull(repo.freshAnchorFor("char-1", nowMillis = now))
        // 超过硬约束档龄 → null（下游按位置不明处理）
        coEvery { dao.latestSnapshotFor("char-1") } returns previous(effectiveAt = now - 12L * 3600_000, capturedAt = now)
        assertNull(repo.freshAnchorFor("char-1", nowMillis = now))
        assertNotNull(repo.freshAnchorFor("char-1", maxAgeMs = 24L * 3600_000, nowMillis = now)) // 软参考档可用
    }

    @Test fun ledger_rejects_blank_key() = runTest {
        repo.recordCompletedEvent(listOf("c1"), emptyMap(), eventKey = "  ", description = "x")
        coVerify(exactly = 0) { dao.insertLedger(any()) }
    }
}
