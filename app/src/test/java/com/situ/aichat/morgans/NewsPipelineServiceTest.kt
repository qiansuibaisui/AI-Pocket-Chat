package com.situ.aichat.morgans

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.NewsPipelineDao
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.NewsDeliveryEntity
import com.situ.aichat.data.local.entity.NewsEventEntity
import com.situ.aichat.data.local.entity.NewsSeverity
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [zCODE] P3·摩根斯新闻管道金样（中继 1：金样 1 生成三要素 / 金样 4 转述层落库 / 金样 6 措辞+防补充红线 / 金样 7 亲历者防重叠）。
 * [zCODE] 中继3 追加：源闸（项1）·跨团距离档**接线实参**（项2·问二教训）·severity floor+手动压级+知情名单（项3/4）。
 */
class NewsPipelineServiceTest {

    private val dao = mockk<NewsPipelineDao>(relaxed = true)
    private val storyStateDao = mockk<StoryStateDao>(relaxed = true)
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val controlSettings = mockk<NewsControlSettings>(relaxed = true)
    private val service = NewsPipelineService(dao, storyStateDao, characterDao, controlSettings)

    @Before
    fun setUp() {
        // 控制面默认=现行为（拍板③）：见面必报/未知源必报、无手动覆盖——金样默认态不变的桩基线
        coEvery { controlSettings.triggerFor(any()) } answers {
            val src: String = firstArg()
            NewsControlSettings.SourceTrigger(enabled = true, probability = if (src == "director_rules") 0.30 else 1.0)
        }
        coEvery { controlSettings.eventOverrideFor(any()) } returns null
    }

    private fun char(uuid: String) = CharacterEntity(uuid = uuid, name = "角色$uuid", creationDate = 0L)

    private fun draft(
        refUuid: String = "session-1",
        charUuid: String = "c1",
        charName: String = "贝克曼",
        loc: String = "香波地",
        severity: NewsSeverity = NewsSeverity.NORMAL,
        sourceRaw: String = "meeting_end",
    ) = NewsEventDraft(
        characterUuid = charUuid, characterName = charName,
        locationKey = loc, eventSummary = "与千岁见面喝酒",
        severity = severity, sourceRefUuid = refUuid, sourceRaw = sourceRaw,
    )

    // ── 金样 1：见面结束 → 新闻条目三要素齐全、结构合法（默认态不变·中继3 回归） ──

    @Test fun publish_creates_event_with_three_elements() = runTest {
        coEvery { dao.eventExistsBySource(any(), any()) } returns false
        coEvery { dao.insertEvent(any()) } returns 1L
        coEvery { storyStateDao.fleetKeyOfCharacter("c1") } returns null
        coEvery { characterDao.getAll() } returns emptyList()
        val uuid = service.publish(draft(), participants = listOf("c1"))
        assertNotNull(uuid)
        val eventSlot = slot<NewsEventEntity>()
        coVerify { dao.insertEvent(capture(eventSlot)) }
        assertEquals("贝克曼", eventSlot.captured.characterName)      // 谁
        assertEquals("香波地", eventSlot.captured.locationKey)        // 在哪
        assertTrue(eventSlot.captured.eventSummary.contains("见面"))   // 干了什么
        assertEquals(NewsSeverity.NORMAL.raw, eventSlot.captured.severityRaw)
    }

    // ── 金样 4：触达角色认知矩阵新增"转述层+来源=摩根斯"条目（同团默认详版保留·拍板①） ──

    @Test fun fan_out_creates_delivery_with_reported_layer_and_morgans_source() = runTest {
        coEvery { dao.eventExistsBySource(any(), any()) } returns false
        coEvery { dao.deliveryExists(any(), any()) } returns false
        coEvery { characterDao.getAll() } returns listOf(char("c1"), char("c2"), char("c3"))
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns "wb" // 全员同团（含事件主角）
        coEvery { storyStateDao.latestSnapshotFor(any()) } returns null
        val delivered = mutableListOf<NewsDeliveryEntity>()
        coEvery { dao.insertEvent(any()) } answers { 1L }
        coEvery { dao.insertDelivery(any()) } answers { delivered.add(firstArg()); 1L }

        service.publish(draft(), participants = listOf("c1"))

        assertTrue("应触达 2 人（c2+c3，c1 亲历排除）·实际=${delivered.size}", delivered.size == 2)
        assertTrue(delivered.all { it.knowledgeLayer == "reported" })
        assertTrue(delivered.all { it.sourceTag == "morgans" })
        assertTrue("同团默认详版保留（拍板①）", delivered.all { it.detailLevel == 2 })
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
        coEvery { characterDao.getAll() } returns listOf(char("c1"), char("c2"))
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns "wb"
        coEvery { storyStateDao.latestSnapshotFor(any()) } returns null
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

    // ── 金样 2·中继 2：延迟三态（同团短延迟/跨团长延迟/无风带不注入）+ 中继3 severity floor ──

    @Test fun delay_three_states() {
        // 同团（不同船）→ 短延迟 6h + 详版 level 2
        val sameFleet = service.calculateDeliveryPlan("wb", "wb", "香波地码头")
        assertNotNull(sameFleet)
        assertEquals(6L * 3600_000, sameFleet!!.delayMs)
        assertEquals(2, sameFleet.detailLevel)
        // 同海域（岛名同）→ 短延迟 + 概述 level 1
        val sameDomain = service.calculateDeliveryPlan(null, "wb", "香波地码头", "香波地港口")
        assertNotNull(sameDomain)
        assertEquals(6L * 3600_000, sameDomain!!.delayMs)
        assertEquals(1, sameDomain.detailLevel)
        // 跨海域/跨团 → 长延迟 24h + 模糊 level 0
        val crossDomain = service.calculateDeliveryPlan("bb", "wb", "香波地码头", "和之国某处")
        assertNotNull(crossDomain)
        assertEquals(24L * 3600_000, crossDomain!!.delayMs)
        assertEquals(0, crossDomain.detailLevel)
        // 无风带（事件地）→ null = 不注入
        assertNull(service.calculateDeliveryPlan("wb", "wb", "九蛇岛港口"))
        // 无风带（目标地）→ null = 不注入
        assertNull(service.calculateDeliveryPlan("wb", "wb", "香波地码头", "九蛇岛"))
        // [zCODE] 中继3·项3：MAJOR → 跨海域模糊档抬到最低概述级（细节不外流）
        val majorCross = service.calculateDeliveryPlan("bb", "wb", "香波地码头", "和之国某处", NewsSeverity.MAJOR)
        assertNotNull(majorCross)
        assertEquals("MAJOR 跨海最低概述级", 1, majorCross!!.detailLevel)
        assertEquals(24L * 3600_000, majorCross.delayMs)
        // NORMAL 不受 floor 影响（默认态不变）
        assertEquals(0, service.calculateDeliveryPlan("bb", "wb", "香波地码头", "和之国某处")!!.detailLevel)
    }

    // ── 金样 3·中继 2：t1/t2 双快照对比（同一事件两个衰减级别） ──

    @Test fun decay_t1_detailed_vs_t2_vague() {
        val event = NewsEventEntity(
            characterUuid = "c1", characterName = "贝克曼",
            locationKey = "香波地码头", eventSummary = "与千岁见面喝蜂蜜威士忌还聊了三艘船的事",
        )
        // t1（同团·短延迟后触达）= 详版
        val t1 = service.formatNarrative(event, 2)
        assertTrue(t1.contains("香波地码头"))
        assertTrue(t1.contains("蜂蜜威士忌"))
        assertTrue(t1.contains("三艘船"))
        // t2（跨团·长延迟后触达）= 模糊
        val t2 = service.formatNarrative(event, 0)
        assertTrue("模糊版应有'有传闻称'", t2.contains("有传闻称"))
        assertFalse("模糊版不应含精确地点", t2.contains("香波地码头"))
        assertFalse("模糊版不应含具体细节", t2.contains("蜂蜜威士忌"))
        // 信息量降级验证
        assertTrue(t2.length < t1.length)
    }

    // ── [zCODE] 中继3·项2 接线金样（问二教训）：跨团目标实参真传——二三档生产可达 ──

    @Test fun fan_out_cross_fleet_tiers_wire_real_target_args() = runTest {
        coEvery { dao.eventExistsBySource(any(), any()) } returns false
        coEvery { dao.deliveryExists(any(), any()) } returns false
        // c1=事件主角（同团 wb·participant）；c2=跨团同海域（roger·锚点香波地）；c3=跨团跨海域（bb·锚点和之国）
        coEvery { characterDao.getAll() } returns listOf(char("c1"), char("c2"), char("c3"))
        coEvery { storyStateDao.fleetKeyOfCharacter("c1") } returns "wb"
        coEvery { storyStateDao.fleetKeyOfCharacter("c2") } returns "roger"
        coEvery { storyStateDao.fleetKeyOfCharacter("c3") } returns "bb"
        coEvery { storyStateDao.latestSnapshotFor("c2") } returns
            StoryAnchorSnapshotEntity(characterUuid = "c2", locationRaw = "香波地港口", effectiveAt = 1L, capturedAt = 1L)
        coEvery { storyStateDao.latestSnapshotFor("c3") } returns
            StoryAnchorSnapshotEntity(characterUuid = "c3", locationRaw = "和之国某处", effectiveAt = 1L, capturedAt = 1L)
        val now = 1_000_000_000_000L
        val delivered = mutableListOf<NewsDeliveryEntity>()
        coEvery { dao.insertEvent(any()) } answers { 1L }
        coEvery { dao.insertDelivery(any()) } answers { delivered.add(firstArg()); 1L }

        service.publish(draft(loc = "香波地码头"), participants = listOf("c1"), nowMillis = now)

        val c2 = delivered.first { it.targetCharacterUuid == "c2" }
        assertEquals("跨团同海域=短延迟", 6L * 3600_000, c2.deliveredAt - now)
        assertEquals("跨团同海域=概述级", 1, c2.detailLevel)
        assertTrue("概述措辞落值", c2.narrativeText.contains("据报道"))
        val c3 = delivered.first { it.targetCharacterUuid == "c3" }
        assertEquals("跨海域=长延迟", 24L * 3600_000, c3.deliveredAt - now)
        assertEquals("跨海域=模糊级", 0, c3.detailLevel)
        assertTrue("模糊措辞落值", c3.narrativeText.contains("有传闻称"))
        assertTrue(delivered.none { it.targetCharacterUuid == "c1" })
    }

    // ── [zCODE] 中继3·项3/4：手动压级+知情名单（优先级 手动 > 闸门(floor) > 默认） ──

    @Test fun manual_override_priority_silence_allowlist_and_downgrade() = runTest {
        val base = {
            coEvery { dao.eventExistsBySource(any(), any()) } returns false
            coEvery { dao.deliveryExists(any(), any()) } returns false
            coEvery { characterDao.getAll() } returns listOf(char("c1"), char("c2"), char("c3"))
            coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns "wb"
            coEvery { storyStateDao.latestSnapshotFor(any()) } returns null
        }
        val delivered = mutableListOf<NewsDeliveryEntity>()
        coEvery { dao.insertEvent(any()) } answers { 1L }
        coEvery { dao.insertDelivery(any()) } answers { delivered.add(firstArg()); 1L }

        // ①零知晓压级（-1）→ 同团默认详版也被全静默
        base()
        coEvery { controlSettings.eventOverrideFor("session-1") } returns NewsControlSettings.EventOverride(detailOverride = -1)
        delivered.clear()
        service.publish(draft(), participants = listOf("c1"))
        assertTrue("零知晓=全静默", delivered.isEmpty())

        // ②知情名单（allowList=["c2"]）→ c2 得知、c3 被拦（同团默认判定被覆盖）
        base()
        coEvery { controlSettings.eventOverrideFor("session-1") } returns NewsControlSettings.EventOverride(allowList = listOf("c2"))
        delivered.clear()
        service.publish(draft(), participants = listOf("c1"))
        assertTrue("仅名单内获知", delivered.map { it.targetCharacterUuid } == listOf("c2"))

        // ③手动压级 0（模糊）> severity floor（MAJOR 本应 ≥1）——手动最高优先
        base()
        coEvery { controlSettings.eventOverrideFor("session-1") } returns NewsControlSettings.EventOverride(detailOverride = 0)
        delivered.clear()
        service.publish(draft(severity = NewsSeverity.MAJOR), participants = listOf("c1"))
        assertEquals("手动压级压过 MAJOR floor", listOf(0, 0), delivered.map { it.detailLevel }.sorted())

        // ④无覆盖（默认态）→ 同团详版照旧（拍板①：同团默认详版保留）
        base()
        coEvery { controlSettings.eventOverrideFor("session-1") } returns null
        delivered.clear()
        service.publish(draft(), participants = listOf("c1"))
        assertEquals(listOf(2, 2), delivered.map { it.detailLevel }.sorted())
    }

    // ── [zCODE] 中继3·项1：每源开关+概率（判定权收编 publish 单口） ──

    @Test fun source_trigger_switch_and_probability_gate() = runTest {
        coEvery { dao.insertEvent(any()) } returns 1L
        coEvery { characterDao.getAll() } returns emptyList()
        // ①开关关 → 零条目零扇出
        coEvery { controlSettings.triggerFor("meeting_end") } returns NewsControlSettings.SourceTrigger(enabled = false)
        assertNull(service.publish(draft(), participants = listOf("c1")))
        coVerify(exactly = 0) { dao.insertEvent(any()) }
        // ②概率 0 → 不过掷点
        coEvery { controlSettings.triggerFor("meeting_end") } returns NewsControlSettings.SourceTrigger(probability = 0.0)
        assertNull(service.publish(draft(), participants = listOf("c1")))
        coVerify(exactly = 0) { dao.insertEvent(any()) }
        // ③默认（开/1.0）→ 照发（默认态不变）
        coEvery { controlSettings.triggerFor("meeting_end") } returns NewsControlSettings.SourceTrigger(enabled = true, probability = 1.0)
        coEvery { dao.eventExistsBySource(any(), any()) } returns false
        assertNotNull(service.publish(draft(), participants = listOf("c1")))
    }
}
