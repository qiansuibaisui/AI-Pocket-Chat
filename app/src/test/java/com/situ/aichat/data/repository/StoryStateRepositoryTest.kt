package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.AnchorSource
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.local.entity.StoryEventSource
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
    private val scheduleDao = mockk<com.situ.aichat.data.local.dao.ScheduleDao>(relaxed = true)
    private val messageRepo = mockk<MessageRepository>(relaxed = true)
    private val repo = StoryStateRepository(dao, scheduleDao, messageRepo)

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

    // ── 读取处5：storyLedger 星标通道（评审附加条件3——source 边界常量） ──

    @Test fun ledger_offline_meeting_inserts_schedule_star_event() = runTest {
        coEvery { dao.insertLedger(any()) } returns 1L
        coEvery { scheduleDao.scheduleFor("c1", any()) } returns null
        coEvery { scheduleDao.eventsForSchedule(any()) } returns emptyList()

        repo.recordCompletedEvent(listOf("c1"), emptyMap(), "meeting-s1", "见面完成", source = StoryEventSource.OFFLINE_MEETING)

        val events = slot<List<com.situ.aichat.data.local.entity.ScheduleEventEntity>>()
        coVerify { scheduleDao.insertEvents(capture(events)) }
        assertEquals("storyLedger", events.captured.single().eventTypeRaw)
        assertEquals("✓", events.captured.single().moodEmoji)
    }

    @Test fun ledger_dialog_ack_never_inserts_star() = runTest {
        // 附加条件3：聊天随口确认（dialog_ack）不插星标——避免日程表被闲聊塞满
        coEvery { dao.insertLedger(any()) } returns 1L
        repo.recordCompletedEvent(listOf("c1"), emptyMap(), "ack-1", "随口确认", source = StoryEventSource.DIALOG_ACK)
        coVerify(exactly = 0) { scheduleDao.insertEvents(any()) }
    }

    @Test fun ledger_duplicate_registration_skips_star() = runTest {
        // 唯一索引命中（rowId=-1 = 重复登记）→ 不插星标（幂等）
        coEvery { dao.insertLedger(any()) } returns -1L
        repo.recordCompletedEvent(listOf("c1"), emptyMap(), "meeting-s1", "见面完成", source = StoryEventSource.OFFLINE_MEETING)
        coVerify(exactly = 0) { scheduleDao.insertEvents(any()) }
    }

    // ── [zCODE] P1·第3项：船团广播（防环/幂等/个体冲突不覆盖） ──

    private fun fleetMember(fleet: String, char: String, convo: String = "") =
        com.situ.aichat.data.local.entity.StoryFleetMemberEntity(fleetKey = fleet, characterUuid = char, conversationUuid = convo)

    @Test fun broadcast_fans_out_with_idempotent_key() = runTest {
        val now = 1_000L
        val trigger = previous(effectiveAt = now - 60_000, capturedAt = now - 60_000)
            .copy(relatedMessageUUID = "msg-1", fleetKey = "whitebeard", sourceRaw = AnchorSource.DIALOG_BLOCK.raw)
        coEvery { dao.membersOfFleet("whitebeard") } returns listOf(fleetMember("whitebeard", "c1", "conv-b"), fleetMember("whitebeard", "c2", "conv-c"))
        coEvery { dao.snapshotExists(any(), any()) } returns false
        coEvery { dao.insertSnapshot(any()) } returns 1L
        coEvery { dao.latestSnapshotFor(any()) } returns null // 成员无个人锚点 → 冲突守卫不触发

        val fanned = repo.broadcastToFleetMates(trigger, nowMillis = now)

        assertEquals(2, fanned)
        val rows = mutableListOf<StoryAnchorSnapshotEntity>()
        coVerify(atLeast = 2) { dao.insertSnapshot(capture(rows)) }
        // 幂等键 "{触发消息uuid}:{目标卡uuid}" + world_sync 源 + 基线时点原样（时效保真）
        assertTrue(rows.all { it.sourceRaw == AnchorSource.WORLD_SYNC.raw })
        assertTrue(rows.any { it.relatedMessageUUID == "msg-1:c1" && it.characterUuid == "c1" })
        assertTrue(rows.any { it.relatedMessageUUID == "msg-1:c2" && it.characterUuid == "c2" })
        assertTrue(rows.all { it.effectiveAt == trigger.effectiveAt })
    }

    @Test fun broadcast_world_sync_source_never_rebroadcasts() = runTest {
        // 防环核心：world_sync 触发行 → 直接 0 扇出（白名单外短路，A→B 后 B 不回写 A）
        val trigger = previous(1L, 1L).copy(sourceRaw = AnchorSource.WORLD_SYNC.raw, fleetKey = "wb", relatedMessageUUID = "m")
        assertEquals(0, repo.broadcastToFleetMates(trigger))
        coVerify(exactly = 0) { dao.insertSnapshot(any()) }
    }

    @Test fun broadcast_idempotent_key_hit_skips_member() = runTest {
        val trigger = previous(1L, 1L).copy(sourceRaw = AnchorSource.DIALOG_BLOCK.raw, fleetKey = "wb", relatedMessageUUID = "m")
        coEvery { dao.membersOfFleet("wb") } returns listOf(fleetMember("wb", "c2"))
        coEvery { dao.snapshotExists("m:c2", AnchorSource.WORLD_SYNC.raw) } returns true // 重复广播
        assertEquals(0, repo.broadcastToFleetMates(trigger))
        coVerify(exactly = 0) { dao.insertSnapshot(any()) }
    }

    @Test fun broadcast_conflicting_personal_anchor_not_overwritten() = runTest {
        // 个体冲突不覆盖：成员有 AGING 内个人锚点且 MotionState 不同 → 跳过写行（world_sync_conflict 计数）
        val now = 1_000_000L
        val trigger = previous(1L, 1L).copy(sourceRaw = AnchorSource.DIALOG_BLOCK.raw, fleetKey = "wb", relatedMessageUUID = "m", motionStateRaw = "sailing")
        coEvery { dao.membersOfFleet("wb") } returns listOf(fleetMember("wb", "c2", "conv-c"))
        coEvery { dao.snapshotExists(any(), any()) } returns false
        coEvery { dao.latestSnapshotFor("c2") } returns previous(now - 3600_000, now - 3600_000).copy(motionStateRaw = "ashore") // 个人在岸
        assertEquals(0, repo.broadcastToFleetMates(trigger, nowMillis = now))
        coVerify(exactly = 0) { dao.insertSnapshot(any()) }
    }

    @Test fun fleet_mates_and_effective_anchor_default_unity() = runTest {
        coEvery { dao.fleetKeyOfCharacter("c1") } returns "wb"
        coEvery { dao.membersOfFleet("wb") } returns listOf(fleetMember("wb", "c1"), fleetMember("wb", "c2"))
        assertEquals(listOf("c2"), repo.fleetMatesFor("c1"))
        // 未配团 → 空（fail-open）
        coEvery { dao.fleetKeyOfCharacter("c9") } returns null
        assertTrue(repo.fleetMatesFor("c9").isEmpty())
        // 默认粒度（BASELINE_PLUS_OFFSET）= 个人最新行
        val personal = previous(5L, 5L)
        coEvery { dao.latestSnapshotFor("c1") } returns personal
        assertEquals(personal.uuid, repo.effectiveAnchorFor("c1")?.uuid)
    }
}
