package com.situ.aichat.schedule

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.NewsPipelineDao
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
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
}
