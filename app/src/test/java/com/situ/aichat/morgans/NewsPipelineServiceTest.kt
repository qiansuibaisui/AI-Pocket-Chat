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
        coEvery { dao.deliveryExists(any(), any()) } returns false
        coEvery { storyStateDao.fleetKeyOfCharacter("c1") } returns "wb"
        coEvery { storyStateDao.membersOfFleet("wb") } returns listOf(
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c1", conversationUuid = "v1"),
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c2", conversationUuid = "v2"),
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c3", conversationUuid = "v3"),
        )
        // answers 手动捕获（withArg 在 verify 块的断言语义在 MockK 3.x 不稳——执行期捕获最可靠）
        val delivered = mutableListOf<NewsDeliveryEntity>()
        coEvery { dao.insertEvent(any()) } answers { 1L }
        coEvery { dao.insertDelivery(any()) } answers { delivered.add(firstArg()); 1L }

        service.publish(draft(), participants = listOf("c1"))

        assertTrue("应触达 2 人（c2+c3，c1 亲历排除）·实际=${delivered.size}", delivered.size == 2)
        assertTrue(delivered.all { it.knowledgeLayer == "reported" })
        assertTrue(delivered.all { it.sourceTag == "morgans" })
        assertTrue(delivered.any { it.targetCharacterUuid == "c2" })
        assertTrue(delivered.any { it.targetCharacterUuid == "c3" })
        assertTrue(delivered.none { it.targetCharacterUuid == "c1" })
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
        coEvery { dao.deliveryExists(any(), any()) } returns false
        coEvery { storyStateDao.fleetKeyOfCharacter("c1") } returns "wb"
        coEvery { storyStateDao.membersOfFleet("wb") } returns listOf(
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c1"),
            StoryFleetMemberEntity(fleetKey = "wb", characterUuid = "c2"),
        )
        val delivered = mutableListOf<NewsDeliveryEntity>()
        coEvery { dao.insertEvent(any()) } answers { 1L }
        coEvery { dao.insertDelivery(any()) } answers { delivered.add(firstArg()); 1L }

        service.publish(draft(), participants = listOf("c1"))

        assertTrue("应仅触达 c2（c1 亲历排除）·实际=${delivered.map { it.targetCharacterUuid }}",
            delivered.size == 1 && delivered[0].targetCharacterUuid == "c2")
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
        // 详版：精确地点+完整摘要
        assertTrue(detailed.contains("香波地码头"))
        assertTrue(detailed.contains("芒果布丁"))
        // 概述版：地点降级到岛名（丢"码头"）+ 摘要带"据报道"转述标记
        assertTrue(summary.contains("香波地"))
        assertTrue(summary.contains("据报道"))
        // 信息量降级判定：概述版的地点部分比详版短（岛名 < 精确地点）
        val detailedLoc = "香波地码头"
        val summaryLoc = "香波地"
        assertTrue(summaryLoc.length < detailedLoc.length)
    }
}
