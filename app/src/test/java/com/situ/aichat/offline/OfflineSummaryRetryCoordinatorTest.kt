package com.situ.aichat.offline

import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.prompt.memory.MemoryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OfflineSummaryRetryCoordinator] 单测：纯函数（时长推算委托 + 24h 自愈轮询公平 pickNextHealCandidate）
 * + **T2-2 DI 编排**（MockK·图纸 §7）——retryOne/applyFallback/自愈 端到端行存编排（构件 Schema 解析 /
 * buildFallbackBody / Renderer / Repository 行构造上一轮已全测，此处只验编排接线）：
 * E5 schema 失败链→兜底行(source=fallback·正文=buildFallbackBody) / E6 幂等 upsert(同 sessionId 稳定键) /
 * E11 恢复路径(scanAndRetry)不回归 / 24h 自愈 source 翻转(fallback→llm + 移出 fallback 列表)。
 */
class OfflineSummaryRetryCoordinatorTest {

    // ══════ T2-2：DI 编排（MockK·图纸 §7）══════

    private val NOW = 2_000_000L
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var memoryRepo: OfflineMeetingMemoryRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var sessionExtractor: OfflineMeetingSessionExtractor
    private lateinit var healStore: OfflineSummaryHealStore
    private lateinit var promiseLedgerService: PromiseLedgerService
    private lateinit var userProfileDao: UserProfileDao

    private val sessionMsgs = listOf(
        MessageEntity(messageUUID = "m1", conversationUuid = "conv", roleRaw = "user", content = "你好呀", timestamp = 1_000L),
        MessageEntity(messageUUID = "m2", conversationUuid = "conv", roleRaw = "assistant", content = "见到你真开心", timestamp = 3_600_000L),
    )
    private val meta = OfflineMeetingSessionExtractor.FallbackMetadata(
        startMillis = 1_000L, location = "咖啡馆", activity = "喝咖啡", finalMood = "warm", initiatedByUser = true,
    )
    private val validJson =
        """{"summary":"今天我们在江边散步聊了很多，从工作聊到理想，还说好下次一起去看海，整个下午都很温柔很放松惬意。","highlights":["记得那杯焦糖拿铁"],"promises":["下次一起去看海"],"mood":"warm"}"""

    private fun convo(failCount: Int = 0, fallbackIds: String = "", offline: Boolean = false) = ConversationEntity(
        uuid = "conv", title = "t", characterUuid = "char", creationDate = 0L, isInOfflineMode = offline,
        pendingOfflineSummarySessionId = "sess", pendingOfflineSummaryFailCount = failCount,
        offlineSummaryFallbackSessionIds = fallbackIds,
    )

    @Before
    fun setUpOrchestration() {
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        memoryRepo = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        sessionExtractor = mockk(relaxed = true)
        healStore = mockk(relaxed = true)
        promiseLedgerService = mockk(relaxed = true)
        userProfileDao = mockk(relaxed = true)

        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "阿泽")
        coEvery { conversationRepo.get("conv") } returns convo()
        coEvery { characterRepo.get("char") } returns CharacterEntity(uuid = "char", name = "小雨", creationDate = 0L)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY) } returns mockk(relaxed = true)
        coEvery { messageRepo.offlineSessionMessages("conv", "sess") } returns sessionMsgs
        coEvery { sessionExtractor.extractFallbackMetadata("conv", "sess", any()) } returns meta

        // formatMessages 内部构造 SimpleDateFormat，裸 JVM 单测环境会 NPE；其产出（会话记录）只喂 LLM prompt，
        // 与行存编排断言无关 → spy MemoryService.Companion 只桩 formatMessages（strippingThinkingTags 等仍走真实现·
        // parseAndValidate 依赖它）。D-2 起见面摘要路传 3 参（llmMessages, userLabel, charLabel），桩匹配 3 参。
        mockkObject(MemoryService.Companion)
        every { MemoryService.formatMessages(any(), any(), any()) } returns "（会话记录）"
    }

    @After fun tearDownOrchestration() = unmockkAll()

    private fun coordinator() = OfflineSummaryRetryCoordinator(
        conversationRepo, messageRepo, characterRepo, memoryRepo, contextLog,
        apiConfigRepo, sessionExtractor, healStore, promiseLedgerService, userProfileDao,
        mockk(relaxed = true), // [zCODE] P1·第2项：storyStateRepository（回流登记 runCatching 容错）
    )

    /** completion 有默认参 → 生产侧经 $default 路由（记录 9 参虚方法调用）：stub 须给全 9 个匹配器。 */
    private fun stubLlm(reply: String) {
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns reply
    }

    // E5：schema 连续失败达阈值 → 写 source=fallback 行（正文=buildFallbackBody 逐字）+ 登记 fallback 列表 + 清 pending。
    @Test fun e5_schemaFailChain_writesFallbackRow() = runBlocking {
        coEvery { conversationRepo.get("conv") } returns convo(failCount = 4) // 本次失败 → 第 5 次 → 兜底
        stubLlm("抱歉，我暂时无法生成") // 非 JSON → parseAndValidate 恒 Failure → runSchemaLLM 返 null → throw → 兜底
        val dur = OfflineSummaryRetryCoordinator.durationFromMessages(sessionMsgs)
        val expectedBody = OfflineSummaryRegenerator.buildFallbackBody(dur, "喝咖啡", 2, "warm", true)

        val outcome = coordinator().retryOne("conv", NOW)

        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK, outcome)
        coVerify(exactly = 1) {
            memoryRepo.upsertMeeting(match { it.sourceRaw == "fallback" && it.sessionId == "sess" && it.summary == expectedBody })
        }
        coVerify(exactly = 1) { conversationRepo.appendFallbackSessionId("conv", "sess") }
        coVerify(exactly = 1) { conversationRepo.clearPendingOfflineSummary("conv") }
        coVerify(exactly = 0) { memoryRepo.upsertMeeting(match { it.sourceRaw == "llm" }) }
    }

    // E6：成功路 upsert 恒带稳定 sessionId 键（Repository 按 sessionId REPLACE·同 session 不产二行）——连跑两次仍同键。
    @Test fun e6_successUpsertsStableSessionKey_noSecondRow() = runBlocking {
        stubLlm(validJson)
        val coord = coordinator()

        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS, coord.retryOne("conv", NOW))
        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS, coord.retryOne("conv", NOW))

        // 两次都以同一 sessionId="sess" 落 source=llm 行 → Repository 幂等合并（行数守恒验见 RepositoryTest）。
        coVerify(exactly = 2) { memoryRepo.upsertMeeting(match { it.sourceRaw == "llm" && it.sessionId == "sess" }) }
        coVerify(exactly = 2) { conversationRepo.clearPendingOfflineSummary("conv") }
    }

    // ── T2-9：见面便车（记忆改造一期·部件②·图纸 §3.10）──

    // 成功路径：把本次见面提取的约定（draft.promises）注册进承诺账本（sourceRaw=meeting·会话/session 透传）。
    @Test fun t2_9_successPath_registersMeetingPromises() = runBlocking {
        stubLlm(validJson) // promises=["下次一起去看海"]
        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS, coordinator().retryOne("conv", NOW))
        coVerify(exactly = 1) {
            promiseLedgerService.registerFromMeeting("char", "conv", "sess", listOf("下次一起去看海"), NOW)
        }
    }

    // E13：约定注册抛错被 runCatching 吞掉，摘要仍 SUCCESS（不影响摘要成败）。
    @Test fun t2_9_registerThrows_stillSuccess_e13() = runBlocking {
        stubLlm(validJson)
        coEvery { promiseLedgerService.registerFromMeeting(any(), any(), any(), any(), any()) } throws RuntimeException("db")
        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS, coordinator().retryOne("conv", NOW))
        coVerify(exactly = 1) { conversationRepo.clearPendingOfflineSummary("conv") } // 成功清理照常
    }

    // 兜底路径（schema 全败 → FELL_BACK）：不走成功路径 → 不注册约定。
    @Test fun t2_9_fallbackPath_doesNotRegister() = runBlocking {
        coEvery { conversationRepo.get("conv") } returns convo(failCount = 4)
        stubLlm("抱歉，我暂时无法生成")
        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK, coordinator().retryOne("conv", NOW))
        coVerify(exactly = 0) { promiseLedgerService.registerFromMeeting(any(), any(), any(), any(), any()) }
    }

    // 日记体人称贯通（图纸 §4/§7）：发给 LLM 的 user prompt 含去「你」化的昵称称呼、日记体指令与禁「用户」守卫。
    @Test fun promptCarriesNicknameAddressAndDiaryVoice() = runBlocking {
        stubLlm(validJson)
        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS, coordinator().retryOne("conv", NOW))
        coVerify(exactly = 1) {
            contextLog.completion(
                any(), any(), any(),
                match { msgs ->
                    val p = msgs.single().content.orEmpty()
                    p.contains("刚才你和阿泽线下见了一面") &&
                        p.contains("提到对方就写「阿泽」，不要用「你」") &&
                        p.contains("像睡前写日记一样") &&
                        p.contains("绝对不要出现「用户」这个词")
                },
                any(), any(), any(), any(), any(),
            )
        }
    }

    // T2-2（图纸 §7·D-2）：见面摘要这一路的 formatMessages 传真名字标签（昵称「阿泽」/角色名「小雨」），非默认「用户/角色」。
    @Test fun t2_2_recordUsesRealNameLabels() = runBlocking {
        stubLlm(validJson)
        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS, coordinator().retryOne("conv", NOW))
        verify(exactly = 1) { MemoryService.formatMessages(any(), "阿泽", "小雨") }
    }

    // E11：恢复路径（scanAndRetry 扫 pending 会话）不回归——v2 内部替换不改触发面，仍产 llm 行 + 清 pending。
    @Test fun e11_recoveryScanPath_stillSummarizes() = runBlocking {
        coEvery { conversationRepo.conversationsWithPendingOfflineSummary() } returns listOf(convo())
        stubLlm(validJson)

        coordinator().scanAndRetry(NOW)

        coVerify(exactly = 1) { memoryRepo.upsertMeeting(match { it.sourceRaw == "llm" && it.sessionId == "sess" }) }
        coVerify(exactly = 1) { conversationRepo.clearPendingOfflineSummary("conv") }
    }

    // 24h 自愈：挑 fallback 行 → LLM 成功升级 → 写 source=llm 行（fallback→llm 翻转）+ 移出 fallback 列表。
    @Test fun heal_upgradesFallbackToLlm_flipsSource() = runBlocking {
        coEvery { healStore.lastHealAt() } returns null // 从未自愈 → 允许
        coEvery { healStore.triedIds() } returns emptyList()
        coEvery { conversationRepo.conversationsWithOfflineFallback() } returns listOf(convo(fallbackIds = "sess"))
        coEvery { messageRepo.conversationUuidForOfflineSession("sess") } returns "conv"
        stubLlm(validJson)

        coordinator().healOneFallbackIfDue(NOW)

        coVerify(exactly = 1) { conversationRepo.restorePendingOfflineSummary("conv", "sess") }
        coVerify(exactly = 1) { memoryRepo.upsertMeeting(match { it.sourceRaw == "llm" && it.sessionId == "sess" }) }
        coVerify(exactly = 1) { conversationRepo.removeFallbackSessionId("conv", "sess") }
    }

    // ── durationFromMessages：委托 OfflineMeetingService.durationText + 空兜底 ──

    private fun msg(ts: Long) = MessageEntity(
        messageUUID = "m$ts", conversationUuid = "c1", roleRaw = "user", content = "x", timestamp = ts,
    )

    @Test fun duration_empty_returnsFallbackText() {
        assertEquals("一段时间", OfflineSummaryRetryCoordinator.durationFromMessages(emptyList()))
    }

    @Test fun duration_delegatesToFirstLast() {
        // 首=0、尾=1h → 委托 durationText → 「约1小时」。
        assertEquals("约1小时", OfflineSummaryRetryCoordinator.durationFromMessages(listOf(msg(0), msg(3_600_000L))))
        // 单条 → 首==尾 → 0 差 → 「不到1分钟」。
        assertEquals("不到1分钟", OfflineSummaryRetryCoordinator.durationFromMessages(listOf(msg(5_000L))))
    }

    // ── pickNextHealCandidate：24h 自愈轮询公平（1:1 iOS）──

    private fun pick(candidates: List<String>, tried: List<String>) =
        OfflineSummaryRetryCoordinator.pickNextHealCandidate(candidates, tried)

    @Test fun heal_emptyCandidates_noPick() {
        val r = pick(emptyList(), listOf("a"))
        assertEquals(null, r.pick)
        assertEquals(listOf("a"), r.updatedTriedIds) // 原样返回
    }

    @Test fun heal_roundRobin_advancesThroughCandidates() {
        assertEquals("a", pick(listOf("a", "b", "c"), emptyList()).pick)
        run {
            val r = pick(listOf("a", "b", "c"), listOf("a"))
            assertEquals("b", r.pick)
            assertEquals(listOf("a", "b"), r.updatedTriedIds)
        }
        run {
            val r = pick(listOf("a", "b", "c"), listOf("a", "b"))
            assertEquals("c", r.pick)
            assertEquals(listOf("a", "b", "c"), r.updatedTriedIds)
        }
    }

    @Test fun heal_allTried_resetsAndStartsNewRound() {
        val r = pick(listOf("a", "b", "c"), listOf("a", "b", "c"))
        assertEquals("a", r.pick)               // 全试过 → 清空重开，取首
        assertEquals(listOf("a"), r.updatedTriedIds)
    }

    @Test fun heal_dropsStaleTriedIdsNotInCandidates() {
        // triedIds 含已失效的 "x"（不在候选里，如成功重试后移出 fallback）→ 清理。
        val r = pick(listOf("a", "b"), listOf("a", "x"))
        assertEquals("b", r.pick)
        assertEquals(listOf("a", "b"), r.updatedTriedIds) // x 被丢弃
    }

    @Test fun heal_dropsStaleAndPicksRemaining() {
        val r = pick(listOf("b", "c"), listOf("a", "b")) // a 失效；b 已试 → 取 c
        assertEquals("c", r.pick)
        assertEquals(listOf("b", "c"), r.updatedTriedIds) // a 丢弃，b 保留 + c
    }
}
