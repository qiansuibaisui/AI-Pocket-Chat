package com.situ.aichat.openloop

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.notification.Notifier
import com.situ.aichat.proactive.ProactiveReplyDeliverer
import com.situ.aichat.prompt.memory.VectorMemoryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [OpenLoopDueMessenger] 行为测试（T2-3/4·图纸 §7·E4/E5/E8）——「到期主动消息真能安全生成/落库/通知」（不止编译过）。
 *
 * 手法照 [com.situ.aichat.offline.OfflineAfterglowServiceTest]：MockK 假掉全部依赖；db.withTransaction 走 mockkStatic
 * 同步跑 block；Notifier/ProcessLifecycleOwner 走 mockkObject/mockkStatic（默认 App 后台 → notify 可达）。
 * 覆盖：五道守卫各自短路 · 校验不过静默放弃(loop 不置 resolved) · happy path 落库+通知+置 resolved · 前台不弹。
 */
class OpenLoopDueMessengerTest {

    private lateinit var context: Context
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var openLoopRepository: OpenLoopRepository
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var vectorMemory: VectorMemoryService
    private lateinit var contextLog: ContextLogService
    private lateinit var db: AppDatabase
    private lateinit var messenger: OpenLoopDueMessenger

    private val character = CharacterEntity(uuid = "char", name = "小雨", creationDate = 0L, personalityDescription = "温柔", speakingStyle = "轻声细语")

    private fun convo(offline: Boolean = false) =
        ConversationEntity(uuid = "conv", title = "t", characterUuid = "char", creationDate = 0L, isInOfflineMode = offline)

    private fun loop(status: String = OpenLoopStatus.OPEN, dueAtFromNow: Long = 0L) = OpenLoopEntity(
        uuid = "loop1", conversationUuid = "conv", characterUuid = "char",
        content = "面试结果", typeRaw = OpenLoopType.USER_EVENT,
        dueAt = System.currentTimeMillis() + dueAtFromNow, createdAt = 0L, statusRaw = status,
    )

    private fun settings(notif: Boolean = true) =
        AppSettings(notificationsEnabled = notif)

    private fun lifecycleOwner(foreground: Boolean): LifecycleOwner {
        val lc = mockk<Lifecycle>()
        every { lc.currentState } returns if (foreground) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        return mockk { every { lifecycle } returns lc }
    }

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        openLoopRepository = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        vectorMemory = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        db = mockk()

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        mockkObject(Notifier)
        every { Notifier.post(any(), any()) } returns Unit
        mockkObject(ProcessLifecycleOwner.Companion)
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner(foreground = false)

        // 默认 = 全守卫通过起点。
        coEvery { settingsRepo.getAppSettings() } returns settings()
        coEvery { openLoopRepository.byUuid("loop1") } returns loop()
        coEvery { conversationRepo.get("conv") } returns convo()
        coEvery { characterRepo.get("char") } returns character
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns mockk(relaxed = true)

        // 投递器用真实现例（依赖全 mock）——落库/分段/通知行为连同守卫编排一起验。
        val deliverer = ProactiveReplyDeliverer(context, conversationRepo, messageRepo, vectorMemory, db)
        messenger = OpenLoopDueMessenger(
            conversationRepo, characterRepo, openLoopRepository, apiConfigRepo,
            settingsRepo, contextLog, deliverer,
            io.mockk.mockk(relaxed = true), // [zCODE] P2·storyStateRepository（锚点接地 runCatching 容错）
        )
    }

    @After fun tearDown() = unmockkAll()

    private fun run() = runBlocking { messenger.deliver("loop1") }

    private fun stubLlm(vararg replies: String) {
        coEvery {
            contextLog.completion(eq(LogSource.OPEN_LOOP_MESSAGE), any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany replies.toList()
    }

    private fun verifyCompletion(times: Int) =
        coVerify(exactly = times) {
            contextLog.completion(eq(LogSource.OPEN_LOOP_MESSAGE), any(), any(), any(), any(), any(), any(), any(), any())
        }

    // ── 五道守卫各自短路 ──

    @Test fun guard1_notificationsDisabled_skipsBeforeLookup() {
        coEvery { settingsRepo.getAppSettings() } returns settings(notif = false)
        run()
        coVerify(exactly = 0) { openLoopRepository.byUuid(any()) } // 守卫①在 loadLoop 之前短路
        verifyCompletion(0)
    }

    @Test fun guard2_loopNotOpen_skips() {
        coEvery { openLoopRepository.byUuid("loop1") } returns loop(status = OpenLoopStatus.RESOLVED)
        run()
        coVerify(exactly = 0) { conversationRepo.get(any()) } // 守卫②在会话查询之前短路（E4）
        verifyCompletion(0)
    }

    @Test fun guard2_loopMissing_skips() {
        coEvery { openLoopRepository.byUuid("loop1") } returns null
        run()
        verifyCompletion(0)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test fun guard3_overduePast2h_skips() {
        coEvery { openLoopRepository.byUuid("loop1") } returns loop(dueAtFromNow = -3L * 60 * 60 * 1000) // 到期已过 3h（E6）
        run()
        coVerify(exactly = 0) { conversationRepo.get(any()) }
        verifyCompletion(0)
    }

    @Test fun guard4_offlineMeeting_skips() {
        coEvery { conversationRepo.get("conv") } returns convo(offline = true)
        run()
        verifyCompletion(0) // E5：见面中静默不发
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    // ── 校验不过 → 静默放弃·loop 保持 open（E8） ──

    @Test fun validationEmpty_givesUpSilently_loopStaysOpen() {
        stubLlm("   ")
        run()
        verifyCompletion(1)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { openLoopRepository.markResolved(any(), any()) } // 不置 resolved
        verify(exactly = 0) { Notifier.post(any(), any()) }
    }

    @Test fun validationTooLong_givesUp() {
        stubLlm("好".repeat(121))
        run()
        verifyCompletion(1)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { openLoopRepository.markResolved(any(), any()) }
    }

    @Test fun validationDirty_givesUp() {
        stubLlm("【今日场景种子】她今天心情不太好") // DirtyMessageDetector 命中 MARKER_TEXT_REPEAT
        run()
        verifyCompletion(1)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { openLoopRepository.markResolved(any(), any()) }
    }

    // ── happy path：落库 + 通知 + 置 resolved ──

    @Test fun happyPath_persistsNotifiesResolves() {
        stubLlm("面试结果出来了没呀？\n\n我从早上就一直惦记着这件事呢，加油鸭～")
        run()
        verifyCompletion(1)
        // 2026-07-07 修订：与普通聊天同口径分段——空行段落各自成泡（短句合并保护等细则同 MessageSplitter）。
        val stored = mutableListOf<MessageEntity>()
        coVerify(atLeast = 1) { messageRepo.upsert(capture(stored)) }
        assert(stored.all { it.roleRaw == "assistant" && !it.isOfflineMode }) { stored.toString() }
        assert(stored.map { it.content } == listOf("面试结果出来了没呀？", "我从早上就一直惦记着这件事呢，加油鸭～")) {
            stored.map { it.content }.toString()
        }
        coVerify(exactly = 1) {
            conversationRepo.applyMaterialization(conversationUuid = eq("conv"), preview = any(), timestamp = any(), markReadNow = eq(false))
        }
        coVerify(exactly = 1) { openLoopRepository.markResolved(match { it.uuid == "loop1" }, any()) }
        verify(exactly = 1) { Notifier.post(any(), any()) } // 多段仍只弹一条通知
    }

    // ── 2026-07-07 修订：system 指令带当前时刻行（日期/星期/时段词） ──

    @Test fun systemInstruction_carriesCurrentMoment() {
        val sent = mutableListOf<List<com.situ.aichat.data.remote.llm.ChatMessageDto>>()
        coEvery {
            contextLog.completion(eq(LogSource.OPEN_LOOP_MESSAGE), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "面试顺利吗？"
        run()
        val system = sent.last().first { it.role == "system" }.content.orEmpty()
        assert(Regex("""现在：\d{4}-\d{2}-\d{2} 周[日一二三四五六] \d{2}:\d{2}（(清晨|上午|中午|下午|晚上|深夜)）""").containsMatchIn(system)) { system }
        assert(system.contains("贴合这个真实时段")) { system }
    }

    @Test fun happyPath_foreground_persistsButNoNotification() {
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner(foreground = true)
        stubLlm("面试顺利吗？")
        run()
        coVerify(exactly = 1) { messageRepo.upsert(any()) }
        coVerify(exactly = 1) { openLoopRepository.markResolved(any(), any()) }
        verify(exactly = 0) { Notifier.post(any(), any()) } // 前台不弹
    }
}
