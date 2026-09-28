package com.situ.aichat.morgans

import com.situ.aichat.data.local.dao.NewsPipelineDao
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.NewsDeliveryEntity
import com.situ.aichat.data.local.entity.NewsEventEntity
import com.situ.aichat.data.local.entity.NewsSeverity
import com.situ.aichat.data.local.entity.StoryFleetMemberEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [zCODE] P3·摩根斯新闻管道金样（中继 1：金样 1 生成三要素 / 金样 4 转述层落库 / 金样 6 措辞+防补充红线 / 金样 7 亲历者防重叠）。
 */
class NewsPipelineServiceTest {

    private val dao = mockk<NewsPipelineDao>(relaxed = true)
    private val storyStateDao = mockk<StoryStateDao>(relaxed = true)
    private val service = NewsPipelineService(dao, storyStateDao)

    private fun draft(refUuid: String = "session-1", charUuid: String = "c1", charName: String = "贝克曼", loc: String = "香波地") =
        NewsEventDraft(
            characterUuid = charUuid, characterName = charName,
            locationKey = loc, eventSummary = "与千岁见面喝酒",
            sourceRefUuid = refUuid,
        )

    // ── 金样 1：见面结束 → 新闻条目三要素齐全、结构合法 ──

    @Test fun publish_creates_event_with_three_elements() = runTest {
        coEvery { dao.eventExistsBySource(any(), any()) } returns false
        coEvery { dao.insertEvent(any()) } returns 1L
        coEvery { storyStateDao.fleetKeyOfCharacter("c1") } returns null
        val uuid = service.publish(draft(), participants = listOf("c1"))
        assertNotNull(uuid)
        val eventSlot = slot<NewsEventEntity>()
        coVerify { dao.insertEvent(capture(eventSlot)) }
        assertEquals("贝克曼", eventSlot.captured.characterName)      // 谁
        assertEquals("香波地", eventSlot.captured.locationKey)        // 在哪
        assertTrue(eventSlot.captured.eventSummary.contains("见面"))   // 干了什么
        assertEquals(NewsSeverity.NORMAL.raw, eventSlot.captured.severityRaw)
    }

    // ── 金样 4：触达角色认知矩阵新增"转述层+来源=摩根斯"条目 ──

    @Test fun fan_out_creates_delivery_with_reported_layer_and_morgans_source() = runTest {
        coEvery { dao.eventExistsBySource(any(), any()) } returns false
        coEvery { dao.insertEvent(any()) } returns 1L
        coEvery { dao.deliveryExists(any(), any()) } returns false
        coEvery { dao.insertDelivery(any()) } returns 1L
        coEvery { storyStateDao.fleetKeyOfCharacter("c1") } returns "wb"
        coEvery { storyStateDao.membersOfFleet("wb") } returns listOf(
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c1", conversationUuid = "v1"),
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c2", conversationUuid = "v2"), // 同团不在场
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c3", conversationUuid = "v3"), // 同团不在场
        )

        service.publish(draft(), participants = listOf("c1"))

        val deliveries = mutableListOf<NewsDeliveryEntity>()
        coVerify(atLeast = 2) { dao.insertDelivery(capture(deliveries)) }
        // 转述层 + 来源标记必带
        assertTrue(deliveries.all { it.knowledgeLayer == "reported" })
        assertTrue(deliveries.all { it.sourceTag == "morgans" })
        // 触达对象：同团非亲历者
        assertTrue(deliveries.any { it.targetCharacterUuid == "c2" })
        assertTrue(deliveries.any { it.targetCharacterUuid == "c3" })
    }

    // ── 金样 6：注入块含"据报道/传闻"强制措辞 + 防补充红线声明 ──

    @Test fun narrative_contains_reported_wording_and_no_supplement_redline() {
        val event = NewsEventEntity(
            characterUuid = "c1", characterName = "贝克曼",
            locationKey = "香波地", eventSummary = "与千岁见面",
        )
        val text = service.formatNarrative(event, detailLevel = 2)
        assertTrue("必须含'据报道'措辞", text.contains("据报道"))
        assertTrue("必须含防补充红线", text.contains("不得自行补充"))
        assertTrue("必须含《世界经济学报》来源", text.contains("世界经济学报"))
    }

    // ── 金样 7（反例）：亲历者不被新闻管道重复注入 ──

    @Test fun participants_not_included_in_news_delivery() = runTest {
        coEvery { dao.eventExistsBySource(any(), any()) } returns false
        coEvery { dao.insertEvent(any()) } returns 1L
        coEvery { dao.deliveryExists(any(), any()) } returns false
        coEvery { dao.insertDelivery(any()) } returns 1L
        coEvery { storyStateDao.fleetKeyOfCharacter("c1") } returns "wb"
        coEvery { storyStateDao.membersOfFleet("wb") } returns listOf(
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c1"), // 亲历者
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c2"), // 不在场
        )

        service.publish(draft(), participants = listOf("c1"))

        val deliveries = mutableListOf<NewsDeliveryEntity>()
        coVerify(atLeast = 1) { dao.insertDelivery(capture(deliveries)) }
        assertTrue("亲历者 c1 不得被新闻管道触达", deliveries.none { it.targetCharacterUuid == "c1" })
        assertTrue("不在场者 c2 应被触达", deliveries.any { it.targetCharacterUuid == "c2" })
    }

    // ── 幂等：同源事件重复发布零行 ──

    @Test fun publish_idempotent_same_source_skips() = runTest {
        coEvery { dao.eventExistsBySource("meeting_end", "session-1") } returns true // 已存在
        assertNull(service.publish(draft(refUuid = "session-1"), participants = listOf("c1")))
        coVerify(exactly = 0) { dao.insertEvent(any()) }
    }

    // ── 细节衰减：概述版 vs 详版措辞差异 ──

    @Test fun detail_level_downgrades_summary() {
        val event = NewsEventEntity(
            characterUuid = "c1", characterName = "贝克曼",
            locationKey = "香波地码头", eventSummary = "与千岁在码头吃了芒果布丁还聊了三个小时",
        )
        val detailed = service.formatNarrative(event, 2)
        val summary = service.formatNarrative(event, 1)
        assertTrue(detailed.contains("香波地码头"))
        assertTrue(detailed.contains("芒果布丁"))
        // 概述版应模糊化地点+摘要截断
        assertTrue(summary.contains("香波地")) // 岛名保留
        assertTrue(summary.length < detailed.length) // 信息量降级
    }
}
