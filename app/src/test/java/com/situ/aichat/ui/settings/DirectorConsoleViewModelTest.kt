package com.situ.aichat.ui.settings

import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import com.situ.aichat.director.DirectorRulesScanWorker
import com.situ.aichat.morgans.NewsControlSettings
import com.situ.aichat.work.BackgroundScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [zCODE] P6 切片一·导演控制台金样（五拍板 2026-10-04）。
 *
 * 行为断言（非补丁存在）：①确认门控——暂存零写库、confirm 才落且落的是暂存值；
 * ②写入留痕——审计行=键名+旧值→新值；③立即扫描——同 worker 类+手动任务键+REPLACE；
 * ④统计映射——dir-r:/dir-m: 前缀分列、今日窗口、枚举位默认 PRIVATE。
 */
class DirectorConsoleViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var newsControl: NewsControlSettings
    private lateinit var storyStateDao: StoryStateDao
    private lateinit var scheduler: BackgroundScheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        newsControl = mockk()
        storyStateDao = mockk()
        scheduler = mockk(relaxed = true)
        stubReads(
            triggers = mapOf(
                "meeting_end" to NewsControlSettings.SourceTrigger(enabled = true, probability = 1.0),
                "director_rules" to NewsControlSettings.SourceTrigger(enabled = true, probability = 0.30),
            ),
            fleetKey = "",
            tuning = NewsControlSettings.DirectorTuning(),
            gate = NewsControlSettings.ProactiveGate(),
            events = emptyList(),
            b9Today = 0,
            b8Count = 0,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubReads(
        triggers: Map<String, NewsControlSettings.SourceTrigger>,
        fleetKey: String,
        tuning: NewsControlSettings.DirectorTuning,
        gate: NewsControlSettings.ProactiveGate,
        events: List<StoryEventLedgerEntity>,
        b9Today: Int,
        b8Count: Int,
    ) {
        coEvery { newsControl.readTriggers() } returns triggers
        coEvery { newsControl.userFleetKeyOverride() } returns fleetKey
        coEvery { newsControl.directorTuning() } returns tuning
        coEvery { newsControl.proactiveGate() } returns gate
        coEvery { storyStateDao.recentDirectorLedger(any()) } returns events
        coEvery { storyStateDao.directorRulesEventCountSince(any()) } returns b9Today
        coEvery { storyStateDao.ledgerCountByKeyPrefixSince(any(), any()) } returns b8Count
    }

    private fun vm() = DirectorConsoleViewModel(newsControl, storyStateDao, scheduler)

    // ── ① 确认门控：暂存零写库 → confirm 落暂存值 ──

    @Test fun confirmGate_stagedChangeWritesNothingUntilConfirmed() {
        val model = vm()
        model.stageGate(model.state.value.gate.copy(enabled = true))
        assertNotNull("暂存后有待确认变更", model.state.value.pending)
        coVerify(exactly = 0) { newsControl.writeProactiveGate(any()) }

        model.confirmPending()
        coVerify(exactly = 1) { newsControl.writeProactiveGate(any()) }
        assertNull("确认后待确认清空", model.state.value.pending)
    }

    @Test fun confirmGate_writesExactlyTheStagedValue() {
        val model = vm()
        val staged = model.state.value.gate.copy(enabled = true, personaCoefficient = 1.5)
        model.stageGate(staged)
        val captured = slot<NewsControlSettings.ProactiveGate>()
        model.confirmPending()
        coVerify { newsControl.writeProactiveGate(capture(captured)) }
        assertEquals(staged, captured.captured)
    }

    @Test fun dismissPending_discardsWithoutWrite() {
        val model = vm()
        model.stageTriggerEnabled("meeting_end", false)
        model.dismissPending()
        assertNull(model.state.value.pending)
        coVerify(exactly = 0) { newsControl.writeTrigger(any(), any()) }
    }

    @Test fun staging_sameValue_doesNothing() {
        val model = vm()
        model.stageGate(model.state.value.gate) // 同值
        assertNull("同值不产生待确认变更（弹窗不扰）", model.state.value.pending)
    }

    @Test fun stageTriggerProbability_writesSourceWithNewProbabilityOnConfirm() {
        val model = vm()
        model.stageTriggerProbability("director_rules", 0.5)
        val captured = slot<NewsControlSettings.SourceTrigger>()
        model.confirmPending()
        coVerify { newsControl.writeTrigger("director_rules", capture(captured)) }
        assertEquals(0.5, captured.captured.probability, 0.0001)
        assertTrue(captured.captured.enabled) // 开关不随概率联动
    }

    @Test fun stageUserFleetKey_trimsBeforeWrite() {
        val model = vm()
        model.stageUserFleetKey("  whitebeard  ")
        val captured = slot<String>()
        model.confirmPending()
        coVerify { newsControl.writeUserFleetKeyOverride(capture(captured)) }
        assertEquals("whitebeard", captured.captured)
    }

    @Test fun stageTuning_writesFullTuningObject() {
        val model = vm()
        val tuned = model.state.value.tuning.copy(dailyCap = 5)
        model.stageTuning(tuned)
        val captured = slot<NewsControlSettings.DirectorTuning>()
        model.confirmPending()
        coVerify { newsControl.writeDirectorTuning(capture(captured)) }
        assertEquals(5, captured.captured.dailyCap)
    }

    // ── ② 写入留痕：审计行格式（与确认弹窗同源三元组） ──

    @Test fun auditLine_isKeyOldNewTriple() {
        assertEquals(
            "write key=proactiveGate old=开关关/拦陌生开/人设系数1.0/白名单0人 new=开关开/拦陌生开/人设系数1.0/白名单0人",
            DirectorConsoleAudit.line(
                "proactiveGate",
                "开关关/拦陌生开/人设系数1.0/白名单0人",
                "开关开/拦陌生开/人设系数1.0/白名单0人",
            ),
        )
    }

    @Test fun pendingChange_carriesAuditTripleForDialog() {
        val model = vm()
        model.stageTriggerEnabled("meeting_end", false)
        val p = model.state.value.pending!!
        assertEquals("trigger.meeting_end.enabled", p.key)
        assertEquals("true", p.oldText)
        assertEquals("false", p.newText)
        // 弹窗与审计共用同一三元组：line() 直接由 pending 产出
        assertEquals("write key=trigger.meeting_end.enabled old=true new=false", DirectorConsoleAudit.line(p.key, p.oldText, p.newText))
    }

    // ── ③ 立即扫描：同 worker 类 + 手动任务键 + REPLACE ──

    @Test fun requestScanNow_enqueuesSameWorkerWithManualKey() {
        val model = vm()
        model.requestScanNow()
        verify(exactly = 1) {
            scheduler.scheduleOneShot(
                uniqueName = DirectorRulesScanWorker.UNIQUE_MANUAL,
                workerClass = DirectorRulesScanWorker::class.java,
                requireNetwork = false,
                existingPolicy = androidx.work.ExistingWorkPolicy.REPLACE,
            )
        }
        assertNotNull("入队回执闪现", model.state.value.scanQueuedAt)
        model.consumeScanFeedback()
        assertNull(model.state.value.scanQueuedAt)
    }

    // ── ④ 统计映射：dir-r:/dir-m: 分列 + 今日窗口 + 枚举位 ──

    @Test fun stats_splitByEventKeyPrefix_andTodayWindowUsesDayStart() {
        val events = listOf(
            ledger("dir-m:host-1:3", t = 3000L),
            ledger("dir-r:ab:20261004", t = 2000L),
            ledger("dir-m:host-1:2", t = 1000L),
        )
        stubReads(
            triggers = emptyMap(), fleetKey = "", tuning = NewsControlSettings.DirectorTuning(),
            gate = NewsControlSettings.ProactiveGate(), events = events, b9Today = 2, b8Count = 5,
        )
        val model = vm()
        val s = model.state.value
        assertTrue(s.loaded)
        assertEquals(2, s.b9.todayCount)
        assertEquals(2000L, s.b9.lastAtMillis) // dir-r 最近一条
        assertEquals(5, s.b8.recentTurnCount)
        assertEquals(3000L, s.b8.lastAtMillis) // dir-m 最近一条
        assertEquals(DirectorConsoleViewModel.ConversationKind.PRIVATE, s.b8.conversationKind) // P5-预埋① 枚举位默认
        assertEquals(3, s.recentEvents.size)
    }

    @Test fun refresh_readsTodayWindowFromDayStart() {
        val model = vm()
        val expected = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        model.refresh()
        coVerify(atLeast = 1) { storyStateDao.directorRulesEventCountSince(expected) }
    }

    private fun ledger(key: String, t: Long) = StoryEventLedgerEntity(characterUuid = "c-$key", eventKey = key, description = "事件-$key", completedAt = t)
}
