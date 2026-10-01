package com.situ.aichat.director

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import com.situ.aichat.data.repository.StoryStateRepository
import com.situ.aichat.morgans.NewsEventDraft
import com.situ.aichat.morgans.NewsPipelineService
import com.situ.aichat.prompt.AnchorBlockParser
import com.situ.aichat.prompt.AnchorVocabulary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/**
 * [zCODE] B9 规则档金样（中继2·终审拍板 2026-10-01）：同位置双层口径/在场排除（C 修正=代理只认线下见面）/
 * 四闸（概率·日上限·单对冷却·eventKey 幂等）/跨团配对/防广播误触（结构性）/{{user}} 侧零写入（结构性=不注入消息仓储）。
 */
class DirectorRulesScanServiceTest {

    private val storyStateDao = mockk<StoryStateDao>(relaxed = true)
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val storyRepo = mockk<StoryStateRepository>(relaxed = true)
    private val newsPipeline = mockk<NewsPipelineService>(relaxed = true)
    private val service = DirectorRulesScanService(storyStateDao, characterDao, storyRepo, newsPipeline)

    private val now = 1_800_000_000_000L // 固定时点（概率闸种子同源判定用）
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun char(uuid: String, name: String) = CharacterEntity(uuid = uuid, name = name, creationDate = 0L)

    private fun anchor(
        uuid: String,
        seaArea: String? = null,
        motionText: String? = null,
        locationRaw: String = "",
        ageMs: Long = 60_000L,
    ): StoryAnchorSnapshotEntity {
        val fleet = if (seaArea != null) {
            AnchorBlockParser.FleetLayer(fleetName = "团", seaArea = seaArea, port = "", shipName = "船", motionText = motionText ?: "停靠")
        } else null
        return StoryAnchorSnapshotEntity(
            characterUuid = uuid,
            locationRaw = locationRaw,
            motionStateRaw = (fleet?.let { AnchorVocabulary.fleetMotionFromText(it.motionText) }?.raw ?: "docked"),
            effectiveAt = now - ageMs,
            capturedAt = now - ageMs,
            fleetLayerJson = AnchorBlockParser.FleetLayerCodec.encode(fleet),
        )
    }

    private fun meetingRow(charUuid: String, ageMs: Long) = StoryEventLedgerEntity(
        characterUuid = charUuid, eventKey = "offline-meeting-x", description = "", completedAt = now - ageMs,
    )

    // ── 同位置双层口径（拍板C） ──

    @Test fun co_location_fleet_layer_preferred() {
        val a = anchor("a", seaArea = "新世界", motionText = "停靠")
        val b = anchor("b", seaArea = "新世界", motionText = "停靠")
        val c = anchor("c", seaArea = "乐园", motionText = "停靠")
        val d = anchor("d", seaArea = "新世界", motionText = "航行中")
        assertTrue(service.sameLocation(a, b))
        assertFalse("海域不同=非同位置", service.sameLocation(a, c))
        assertFalse("MotionState 不同=非同位置", service.sameLocation(a, d))
    }

    @Test fun co_location_fallback_island_top_when_no_fleet_layer() {
        val a = anchor("a", locationRaw = "香波地·码头")  // 岛名顶层=香波地·DOCKED
        val b = anchor("b", locationRaw = "香波地·港口")  // 同岛同状态
        val e = anchor("e", locationRaw = "水之都·码头")  // 异岛
        assertTrue(service.sameLocation(a, b))
        assertFalse(service.sameLocation(a, e))
    }

    @Test fun unknown_indoors_transit_ineligible() {
        assertTrue("词表不识→无坐标", service.coLocationOf(anchor("a", locationRaw = "某处")) == null)
        assertTrue("室内不可断言同地", !service.sameLocation(anchor("a", locationRaw = "卧舱"), anchor("b", locationRaw = "寝室")))
    }

    // ── 在场排除（C 修正：代理只认线下见面·电话虫异地通话结构性不计入） ──

    @Test fun scan_aborts_fail_closed_when_no_recent_offline_meeting() = runTest {
        coEvery { storyStateDao.latestOfflineMeetingLedger() } returns null
        assertEquals("无代理→整轮放弃", 0, service.scanOnce(now, Random(1), zone))
        coVerify(exactly = 0) { characterDao.getAll() } // 连全库扫描都不启动
    }

    @Test fun scan_aborts_when_meeting_older_than_proxy_window() = runTest {
        coEvery { storyStateDao.latestOfflineMeetingLedger() } returns meetingRow("x", ageMs = 25L * 3600_000)
        assertEquals("代理超 24h→失效放弃", 0, service.scanOnce(now, Random(1), zone))
    }

    @Test fun proxy_comes_only_from_offline_meeting_source() = runTest {
        // 拍板C 结构性证据：代理唯一取材口=latestOfflineMeetingLedger（DAO source='offline_meeting' 过滤——
        // 电话虫/异地通话不写该源，代理不会漂到对方船上）
        coEvery { storyStateDao.latestOfflineMeetingLedger() } returns meetingRow("x", ageMs = 3600_000)
        coEvery { storyStateDao.latestSnapshotFor("x") } returns anchor("x", seaArea = "东海", motionText = "停靠")
        coEvery { characterDao.getAll() } returns emptyList()
        service.scanOnce(now, Random(1), zone)
        coVerify(exactly = 1) { storyStateDao.latestOfflineMeetingLedger() }
        coVerify(exactly = 1) { storyStateDao.latestSnapshotFor("x") } // 代理锚点取自见面角色
    }

    @Test fun pair_with_user_co_located_is_rejected() = runTest {
        // 用户（代理=白团海域A）与 a 同位置 → (a,b) 非远方 → 不触发
        coEvery { storyStateDao.latestOfflineMeetingLedger() } returns meetingRow("x", ageMs = 3600_000)
        coEvery { storyStateDao.latestSnapshotFor("x") } returns anchor("x", seaArea = "海域A", motionText = "停靠")
        coEvery { characterDao.getAll() } returns listOf(char("a", "艾斯"), char("b", "贝拉"))
        coEvery { storyStateDao.latestSnapshotFor("a") } returns anchor("a", seaArea = "海域A", motionText = "停靠")
        coEvery { storyStateDao.latestSnapshotFor("b") } returns anchor("b", seaArea = "海域A", motionText = "停靠")
        coEvery { storyStateDao.fleetKeyOfCharacter(any()) } returns null
        assertEquals("用户在场(代理同位置)→零触发", 0, service.scanOnce(now, Random(1), zone))
        coVerify(exactly = 0) { storyRepo.applyDirectorEvent(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── 四闸 + 触发 + 防广播 ──

    /** 标准绿场：代理在异海域，a/b 同位置跨团，四闸全过（种子预演判定）。 */
    private fun greenField(seed: Int): Random {
        coEvery { storyStateDao.latestOfflineMeetingLedger() } returns meetingRow("x", ageMs = 3600_000)
        coEvery { storyStateDao.latestSnapshotFor("x") } returns anchor("x", seaArea = "东海", motionText = "停靠")
        coEvery { characterDao.getAll() } returns listOf(char("a-uuid", "艾斯"), char("b-uuid", "贝拉"))
        coEvery { storyStateDao.latestSnapshotFor("a-uuid") } returns anchor("a-uuid", seaArea = "新世界", motionText = "停靠")
        coEvery { storyStateDao.latestSnapshotFor("b-uuid") } returns anchor("b-uuid", seaArea = "新世界", motionText = "停靠")
        coEvery { storyStateDao.fleetKeyOfCharacter("a-uuid") } returns "wb"
        coEvery { storyStateDao.fleetKeyOfCharacter("b-uuid") } returns "roger"
        coEvery { storyStateDao.directorRulesEventCountSince(any()) } returns 0
        coEvery { storyStateDao.ledgerCountByKeyPrefixSince(any(), any()) } returns 0
        return Random(seed)
    }

    @Test fun trigger_fires_with_canonical_event_key_and_optional_news() = runTest {
        val seed = 7
        // 种子预演：与 service 内同序消费 nextDouble（概率闸→报道闸）→ 断言与随机序列一致而非硬编码
        val probe = Random(seed)
        val willFire = probe.nextDouble() < DirectorRulesConfig.TRIGGER_PROBABILITY
        val willReport = probe.nextDouble() < DirectorRulesConfig.NEWS_REPORT_PROBABILITY
        val fired = service.scanOnce(now, Random(seed), zone)
        assertEquals(if (willFire) 1 else 0, fired)
        if (willFire) {
            val pairKey = service.pairKeyOf(char("a-uuid", "艾斯"), char("b-uuid", "贝拉"))
            val expectedKey = service.eventKeyOf(pairKey, now, zone)
            coVerify(exactly = 1) { storyRepo.applyDirectorEvent(listOf("a-uuid", "b-uuid"), any(), any(), any(), expectedKey, any(), any()) }
            assertTrue(expectedKey.startsWith("dir-r:") && expectedKey.contains(":20270115")) // pairKey 排序由 pairKeyOf 唯一定义
            coVerify(exactly = if (willReport) 1 else 0) {
                newsPipeline.publish(any<NewsEventDraft>(), listOf("a-uuid", "b-uuid"), any())
            }
        }
    }

    @Test fun cooldown_gate_blocks_pair() = runTest {
        greenField(11)
        coEvery { storyStateDao.ledgerCountByKeyPrefixSince(any(), any()) } returns 1 // 72h 内已有同对事件
        assertEquals(0, service.scanOnce(now, Random(11), zone))
        coVerify(exactly = 0) { storyRepo.applyDirectorEvent(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun daily_cap_gate_blocks_all() = runTest {
        greenField(11)
        coEvery { storyStateDao.directorRulesEventCountSince(any()) } returns DirectorRulesConfig.DAILY_CAP
        assertEquals(0, service.scanOnce(now, Random(11), zone))
        coVerify(exactly = 0) { storyRepo.applyDirectorEvent(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun same_fleet_pair_is_not_an_encounter() = runTest {
        greenField(11)
        coEvery { storyStateDao.fleetKeyOfCharacter("b-uuid") } returns "wb" // 同团
        assertEquals("同团=日常同船生活，不产相遇事件", 0, service.scanOnce(now, Random(11), zone))
        coVerify(exactly = 0) { storyRepo.applyDirectorEvent(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun encounter_never_broadcasts_and_news_excludes_participants_report_only() = runTest {
        // 多粒度种子跑三轮：只要触发，必无 broadcast（B9 不走广播/⚓——佩罗娜教训同源）
        for (seed in intArrayOf(3, 7, 11, 42)) {
            greenField(seed)
            service.scanOnce(now, Random(seed), zone)
        }
        coVerify(exactly = 0) { storyRepo.broadcastToFleetMates(any(), any()) }
    }

    @Test fun event_key_granularity_locked() {
        // 拍板A：dir-r:{pairKey}:{yyyyMMdd}——每对每日一记（唯一索引即"每对每日≤1"硬闸）
        val pairKey = service.pairKeyOf(char("a", "艾斯"), char("b", "贝拉"))
        val key = service.eventKeyOf(pairKey, now, zone)
        assertTrue("前缀+日期桶锁定: $key", key == "dir-r:${pairKey}:20270115")
    }
}
