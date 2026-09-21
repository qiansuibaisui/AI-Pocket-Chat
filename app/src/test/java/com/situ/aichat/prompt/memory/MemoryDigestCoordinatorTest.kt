package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.prompt.memory.MemoryDigestMaterialService.MaterialBundle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 消化班车编排（记忆改造一期·图纸 §3.6 / T2-5/6/7）。断言从图纸 §3.6/§5 独立反推（MockK 假掉六依赖）：
 * 成功序（摘要→标记→对账→落库）、摘要抛错不标记不对账（E7）、对账失败重试一次仍败静默不上抛（E6）、
 * 同角色并发互斥串行（E9）。Robolectric：对账 nowText 用 android.text.format.DateFormat。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDigestCoordinatorTest {

    private lateinit var summaryCoordinator: MemorySummaryCoordinator
    private lateinit var memoryService: MemoryService
    private lateinit var materialService: MemoryDigestMaterialService
    private lateinit var contextLog: ContextLogService
    private lateinit var ledger: PromiseLedgerService
    private lateinit var promiseRepository: PromiseRepository
    private lateinit var coordinator: MemoryDigestCoordinator

    private val config = mockk<ApiConfigValues>(relaxed = true)
    private val character = CharacterEntity(uuid = "c1", name = "团子", creationDate = 0L)
    private val messages = emptyList<MessageEntity>()
    private val bundle = MaterialBundle(text = "素材文本", meetingUuids = listOf("m1"), diaryUuids = listOf("d1"), momentsWatermarkAdvanceTo = 123L, lineCount = 2)

    @Before fun setUp() {
        summaryCoordinator = mockk(relaxed = true)
        memoryService = mockk(relaxed = true)
        materialService = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        ledger = mockk(relaxed = true)
        promiseRepository = mockk(relaxed = true)
        coordinator = MemoryDigestCoordinator(summaryCoordinator, memoryService, materialService, contextLog, ledger, promiseRepository, io.mockk.mockk(relaxed = true)) // [zCODE] P2·LB-5 momentRepo

        coEvery { materialService.collect(any(), any(), any(), any(), any()) } returns bundle
        coEvery { promiseRepository.openByCharacter(any()) } returns emptyList()
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns """{"changes":[],"new":[]}"""
        coEvery {
            summaryCoordinator.summarizeAndPersist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "新记忆"
    }

    private suspend fun run() = coordinator.digestAndReconcile(character, "conv1", messages, config, AppSettings(), "小明")

    // ── T2-5 成功序 + 摘要抛错 ──

    @Test fun successOrder_summarize_thenMark_thenReconcile_thenApply() = runTest {
        val result = run()
        assertEquals("新记忆", result)
        coVerifyOrder {
            materialService.collect(any(), any(), any(), any(), any())
            summaryCoordinator.summarizeAndPersist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            materialService.markDigested("c1", bundle, any())
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
            ledger.applyReconciliation(eq("c1"), eq("conv1"), any(), any())
        }
    }

    @Test fun summaryThrows_noMark_noReconcile_propagates_e7() = runTest {
        coEvery {
            summaryCoordinator.summarizeAndPersist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws MemorySummaryError.EmptyResponse

        var threw = false
        try { run() } catch (e: MemorySummaryError) { threw = true }

        assertTrue("摘要抛错应上抛", threw)
        coVerify(exactly = 0) { materialService.markDigested(any(), any(), any()) }
        coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { ledger.applyReconciliation(any(), any(), any(), any()) }
    }

    // ── T2-6 对账失败重试一次仍败 → 静默、标记保留、不上抛 ──

    @Test fun reconcileFails_retriesOnce_thenSilentlyGivesUp_markKept_e6() = runTest {
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("network")

        val result = run() // 不上抛
        assertEquals("新记忆", result)
        coVerify(exactly = 2) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } // 首次 + 重试一次
        coVerify(exactly = 1) { materialService.markDigested("c1", bundle, any()) } // 标记保留（摘要成果保留）
        coVerify(exactly = 0) { ledger.applyReconciliation(any(), any(), any(), any()) } // 两次都没到落库
    }

    @Test fun reconcile_runsEvenWhenOpenEmpty() = runTest {
        run()
        // open 空也照跑对账（新约定提取仍需要）：一次 LLM 调用 + 落库。
        coVerify(exactly = 1) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.applyReconciliation(any(), any(), any(), any()) }
    }

    // ── T2-7 同角色并发互斥 ──

    @Test fun concurrentSameCharacter_serializedByMutex_e9() = runTest {
        var active = 0
        var maxActive = 0
        coEvery {
            summaryCoordinator.summarizeAndPersist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            active++
            maxActive = maxOf(maxActive, active)
            delay(50)
            active--
            "新记忆"
        }

        val j1 = launch { run() }
        val j2 = launch { run() }
        j1.join(); j2.join()

        assertEquals("per-角色互斥 → 同刻至多 1 个在摘要段", 1, maxActive)
    }
}
