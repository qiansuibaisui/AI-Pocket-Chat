package com.situ.aichat.schedule

import com.situ.aichat.data.local.dao.NewsPipelineDao
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.NewsDeliveryEntity
import com.situ.aichat.data.local.entity.NewsEventEntity
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import com.situ.aichat.data.local.entity.StoryFleetMemberEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P4·A5/A6 金样：四级权限拦截+放行正例+样本 #1/#2 端到端断言。
 */
class RelationGateServiceTest {
    private val storyStateDao = mockk<StoryStateDao>(relaxed = true)
    private val newsDao = mockk<NewsPipelineDao>(relaxed = true)
    private val service = RelationGateService(storyStateDao, newsDao)

    private fun ledger(charUuid: String, key: String, desc: String = "", at: Long = System.currentTimeMillis()) =
        StoryEventLedgerEntity(characterUuid = charUuid, eventKey = key, description = desc, completedAt = at)

    // ── ① 队友：同团放行 ──
    @Test fun fleet_mate_same_fleet_passes() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter("beckman") } returns "wb"
        coEvery { storyStateDao.fleetKeyOfCharacter("bella") } returns "wb"
        assertTrue(service.canAppearInSchedule("beckman", "bella"))
    }

    // ── ② 零级/未演出：拦截 ──
    @Test fun stranger_no_records_blocked() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList()
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList()
        assertFalse("零级禁现", service.canAppearInSchedule("beckman", "bella"))
    }

    // ── ③ 新识：ledger 有共同事件放行 ──
    @Test fun acquainted_ledger_event_passes() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor("bella", any()) } returns listOf(
            ledger("bella", "joint-chitose-cleanup", "与千岁在甲板擦船")
        )
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList()
        assertTrue("③新识（ledger 有事件）放行", service.canAppearInSchedule("bella", "chitose-cleanup"))
    }

    // ── 核心正例：已演出未登记放行 ──
    @Test fun performed_but_unregistered_passes() = runTest {
        // ledger 有事件（已演出），但角色卡无跨团关系登记（不存在该列）→ 放行
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor("bella", any()) } returns listOf(
            ledger("bella", "event-with-chitose", "见面")
        )
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList()
        assertTrue("已演出未登记放行（演出为准不拦登记）", service.canAppearInSchedule("bella", "chitose"))
    }

    // ── ② 知晓（单向）：拦截 ──
    @Test fun aware_oneway_blocked_for_schedule() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList()
        val delivery = NewsDeliveryEntity(newsEventUuid = "e1", targetCharacterUuid = "bella")
        coEvery { newsDao.deliveredTo("bella", any(), any()) } returns listOf(delivery)
        coEvery { newsDao.getEvent("e1") } returns NewsEventEntity(characterUuid = "beckman")
        coEvery { newsDao.deliveredTo("beckman", any(), any()) } returns emptyList()
        assertFalse("②单向知晓禁现", service.canAppearInSchedule("beckman", "bella"))
    }

    // ── 样本 #1 端到端：贝克曼×贝拉·跨团零级·拦截 ──
    @Test fun sample1_beckman_bella_cross_fleet_zero_level_blocked() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter("beckman") } returns "wb" // 贝克曼=白团
        coEvery { storyStateDao.fleetKeyOfCharacter("bella") } returns "roger" // 贝拉=另一团
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList() // 无演出
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList() // 无报道
        assertFalse("样本#1：跨团零级'和「贝拉」巡视'拦截", service.canAppearInSchedule("beckman", "bella"))
    }

    // ── 样本 #2 端到端：贝拉×千岁·无演出链·拒生成 ──
    @Test fun sample2_bella_chitose_no_provenance_rejected() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList() // 无演出链
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList() // 无新闻触达
        assertFalse("样本#2：'和「千岁」擦甲板'无演出链拒生成", service.hasProvenanceForJointActivity("bella", "chitose"))
    }
}
