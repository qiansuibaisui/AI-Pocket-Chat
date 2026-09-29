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
import org.junit.Assert.assertEquals
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
        coEvery { storyStateDao.getRelation(any(), any()) } returns null // [zCODE] relaxed mock 防假对象
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
        coEvery { storyStateDao.getRelation(any(), any()) } returns null // relaxed mock 可能造出非 null 假对象
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor("bella", any()) } returns listOf(
            ledger("bella", "joint-chitose-cleanup", "与千岁在甲板擦船")
        )
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList()
        assertTrue("③新识（ledger 有事件）放行", service.canAppearInSchedule("bella", "chitose-cleanup"))
    }

    // ── 核心正例：已演出未登记放行 ──
    @Test fun performed_but_unregistered_passes() = runTest {
        coEvery { storyStateDao.getRelation(any(), any()) } returns null // 同上
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

    // ── ②双向：仅低频互动类放行（A5 闸门仍拦截共同活动条目）──
    @Test fun aware_mutual_blocked_for_schedule_too() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList()
        val dA = NewsDeliveryEntity(newsEventUuid = "e1", targetCharacterUuid = "a")
        val dB = NewsDeliveryEntity(newsEventUuid = "e2", targetCharacterUuid = "b")
        coEvery { newsDao.deliveredTo("a", any(), any()) } returns listOf(dA)
        coEvery { newsDao.getEvent("e1") } returns NewsEventEntity(characterUuid = "b")
        coEvery { newsDao.deliveredTo("b", any(), any()) } returns listOf(dB)
        coEvery { newsDao.getEvent("e2") } returns NewsEventEntity(characterUuid = "a")
        assertFalse("②双向仍不可共同活动条目", service.canAppearInSchedule("a", "b"))
    }

    // ── 手动登记④旧识：直接放行（第四源·最高优先）──
    @Test fun manual_old_friend_registration_passes() = runTest {
        coEvery { storyStateDao.getRelation("garp", "sengoku") } returns com.situ.aichat.data.local.entity.CharacterRelationEntity(
            fromUuid = "garp", toUuid = "sengoku", level = "old_friend", source = "原著既定",
        )
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null // 不同团
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList() // 无演出记录
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList() // 无报道
        assertTrue("手动登记④旧识直接放行（先天误拦解除）", service.canAppearInSchedule("garp", "sengoku"))
    }

    // ── 样本 #2 端到端：贝拉×千岁·无演出链·拒生成 ──
    @Test fun sample2_bella_chitose_no_provenance_rejected() = runTest {
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList() // 无演出链
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList() // 无新闻触达
        assertFalse("样本#2：'和「千岁」擦甲板'无演出链拒生成", service.hasProvenanceForJointActivity("bella", "chitose"))
    }

    // ── 管线级样本 #1：贝克曼日程含"和「贝拉」巡视"→ 闸门过滤后不含贝拉 ──

    @Test fun pipeline_sample1_beckman_schedule_with_bella_filtered() = runTest {
        val charDao = mockk<com.situ.aichat.data.local.dao.CharacterDao>(relaxed = true)
        val gateWithDao = RelationGateService(storyStateDao, newsDao, charDao)
        // 贝拉角色可查到（名→UUID 解析成功）
        val bellaChar = com.situ.aichat.data.local.entity.CharacterEntity(uuid = "bella-uuid", name = "贝拉", creationDate = 0L)
        coEvery { charDao.getByName("贝拉") } returns bellaChar
        // 贝克曼=白团，贝拉=罗杰团（跨团零级）
        coEvery { storyStateDao.getRelation(any(), any()) } returns null
        coEvery { storyStateDao.fleetKeyOfCharacter("beckman") } returns "wb"
        coEvery { storyStateDao.fleetKeyOfCharacter("bella-uuid") } returns "roger"
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList()
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList()

        val events = listOf(
            com.situ.aichat.data.local.entity.ScheduleEventEntity(
                uuid = "e1", scheduleUuid = "s1", startTime = 0, endTime = 100,
                activity = "和「贝拉」巡视甲板", relatedCharacterNames = "贝拉",
            ),
            com.situ.aichat.data.local.entity.ScheduleEventEntity(
                uuid = "e2", scheduleUuid = "s1", startTime = 200, endTime = 300,
                activity = "独自练刀", relatedCharacterNames = null, // 纯个人条目
            ),
        )
        val filtered = gateWithDao.filterScheduleEvents("beckman", events)
        assertEquals("纯个人条目保留", 1, filtered.size)
        assertEquals("独自练刀", filtered[0].activity) // '和「贝拉」巡视'被拦截
    }

    // ── 管线级样本 #2：贝拉日程含"和「千岁」擦甲板"无演出链→ 拦截 ──

    @Test fun pipeline_sample2_bella_schedule_with_chitose_filtered() = runTest {
        val charDao = mockk<com.situ.aichat.data.local.dao.CharacterDao>(relaxed = true)
        val gateWithDao = RelationGateService(storyStateDao, newsDao, charDao)
        val chitoseChar = com.situ.aichat.data.local.entity.CharacterEntity(uuid = "chitose-uuid", name = "千岁", creationDate = 0L)
        coEvery { charDao.getByName("千岁") } returns chitoseChar
        coEvery { storyStateDao.getRelation(any(), any()) } returns null
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null // 不同团
        coEvery { storyStateDao.recentLedgerFor(any(), any()) } returns emptyList() // 无演出链
        coEvery { newsDao.deliveredTo(any(), any(), any()) } returns emptyList() // 无报道

        val events = listOf(
            com.situ.aichat.data.local.entity.ScheduleEventEntity(
                uuid = "e1", scheduleUuid = "s1", startTime = 0, endTime = 100,
                activity = "和「千岁」擦甲板", relatedCharacterNames = "千岁",
            ),
        )
        val filtered = gateWithDao.filterScheduleEvents("bella", events)
        assertEquals("无演出链共同活动条目被拦截", 0, filtered.size)
    }
}
