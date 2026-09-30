package com.situ.aichat.schedule

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.NewsPipelineDao
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.prompt.AnchorBlockParser
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] B组#5 闸门双卡金样：存量污染日程在 prompt 组装前即刻失效（读料侧重跑 A5 过滤器）。
 * ZD-10 修复——贝克曼装包前脏日程"和「贝拉」巡视"被回复复述+写进新锚点在场名单→贝拉"同时出现在两条船"。
 */
class GateDoubleCardTest {
    private val storyStateDao = mockk<StoryStateDao>(relaxed = true)
    private val newsDao = mockk<NewsPipelineDao>(relaxed = true)
    private val charDao = mockk<CharacterDao>(relaxed = true)
    private val gate = RelationGateService(storyStateDao, newsDao, charDao)

    @Test fun legacy_dirty_schedule_filtered_at_read_time() = runTest {
        // 存量污染：贝克曼日程 DB 里有"和「贝拉」巡视"（生成时 A5 未上线·闸门前残留）
        val bella = CharacterEntity(uuid = "bella-uuid", name = "贝拉", creationDate = 0L)
        coEvery { charDao.getByName("贝拉") } returns bella
        coEvery { storyStateDao.getRelation(any(), any()) } returns null
        coEvery { storyStateDao.fleetKeyOfCharacter("beckman") } returns "wb"
        coEvery { storyStateDao.fleetKeyOfCharacter("bella-uuid") } returns "roger" // 跨团
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList()
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList()

        val dirtyEvents = listOf(
            ScheduleEventEntity(uuid = "e1", scheduleUuid = "s1", startTime = 0, endTime = 100,
                activity = "和「贝拉」巡视甲板", relatedCharacterNames = "贝拉"),
            ScheduleEventEntity(uuid = "e2", scheduleUuid = "s1", startTime = 200, endTime = 300,
                activity = "独自练刀", relatedCharacterNames = null),
        )
        // 读料侧重跑闸门（同一过滤器·第二调用点）
        val filtered = gate.filterScheduleEvents("beckman", dirtyEvents)
        assertEquals("存量脏条目被拦、干净条目保留", 1, filtered.size)
        assertEquals("独自练刀", filtered[0].activity)
    }

    @Test fun clean_schedule_passes_through_unchanged() = runTest {
        coEvery { storyStateDao.getRelation(any(), any()) } returns null
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList()
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList()
        val clean = listOf(
            ScheduleEventEntity(uuid = "e1", scheduleUuid = "s1", startTime = 0, endTime = 100,
                activity = "独自练刀", relatedCharacterNames = null),
        )
        val filtered = gate.filterScheduleEvents("anyone", clean)
        assertEquals("无关联角色的纯个人条目全放行", 1, filtered.size)
        assertTrue(filtered[0].activity == "独自练刀")
    }

    // ── [zCODE] 工单#1·金样③ MotionState 不一致 A3 拦截（认知边界六.2「无靠港记录不得出现陆地场景」的日程落点） ──

    /** fresh 航行中锚点（全团层=舰级事实源）。 */
    private fun sailingAnchor(now: Long, ageMs: Long = 60_000L) = com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity(
        characterUuid = "marco",
        eventName = "瞭望",
        locationRaw = "甲板",
        motionStateRaw = "sailing",
        effectiveAt = now - ageMs,
        capturedAt = now,
        fleetLayerJson = AnchorBlockParser.FleetLayerCodec.encode(
            AnchorBlockParser.FleetLayer(fleetName = "白胡子海贼团", seaArea = "新世界", port = "", shipName = "莫比迪克号", motionText = "航行中"),
        ),
    )

    @Test fun workorder1_sailing_anchor_blocks_landing_events() = runTest {
        val now = System.currentTimeMillis()
        coEvery { storyStateDao.latestSnapshotFor("marco") } returns sailingAnchor(now)
        val events = listOf(
            ScheduleEventEntity(uuid = "e1", scheduleUuid = "s1", startTime = 0, endTime = 100,
                location = "香波地集市", activity = "登陆采购补给", relatedCharacterNames = null),
            ScheduleEventEntity(uuid = "e2", scheduleUuid = "s1", startTime = 200, endTime = 300,
                location = "甲板", activity = "甲板巡哨", relatedCharacterNames = null),
        )
        val filtered = gate.filterScheduleEvents("marco", events)
        assertEquals("fresh 航行中：登陆条目拦、船上条目留（拦条不杀批）", 1, filtered.size)
        assertEquals("甲板巡哨", filtered[0].activity)
    }

    @Test fun workorder1_stale_or_docked_anchor_fail_open() = runTest {
        val now = System.currentTimeMillis()
        // 超龄（30h）航行锚点：位置按不明处理 → 不作硬约束，登陆条目放行
        coEvery { storyStateDao.latestSnapshotFor("stale") } returns sailingAnchor(now, ageMs = 30L * 3600_000)
        // fresh 但停靠（全团层 motionText=停靠 + motionStateRaw=docked）→ 非航行不拦
        coEvery { storyStateDao.latestSnapshotFor("docked") } returns sailingAnchor(now).copy(
            motionStateRaw = "docked",
            fleetLayerJson = AnchorBlockParser.FleetLayerCodec.encode(
                AnchorBlockParser.FleetLayer("白胡子海贼团", "新世界", "香波地泊地", "莫比迪克号", "停靠"),
            ),
        )
        // 无锚点 → 全放行
        coEvery { storyStateDao.latestSnapshotFor("noanchor") } returns null
        val landing = listOf(
            ScheduleEventEntity(uuid = "e1", scheduleUuid = "s1", startTime = 0, endTime = 100,
                location = "香波地集市", activity = "登陆采购补给", relatedCharacterNames = null),
        )
        assertEquals("超龄锚点不硬拦", 1, gate.filterScheduleEvents("stale", landing).size)
        assertEquals("停靠锚点放行岸上条目", 1, gate.filterScheduleEvents("docked", landing).size)
        assertEquals("无锚点放行", 1, gate.filterScheduleEvents("noanchor", landing).size)
    }
}
