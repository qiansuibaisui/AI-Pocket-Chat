package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.meeting.MeetingProposalCoordinator
import com.situ.aichat.meeting.MeetupNotificationService
import com.situ.aichat.notification.MeetupArrivalTarget
import com.situ.aichat.notification.NotificationNavigator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * ChatMeetingController 行为测试——验证「未来约定见面」域协作者抽出后「真的能用」（不止编译过）。
 *
 * 手法（对齐 [ChatOfflineControllerTest]）：MockK 假掉 coordinator/dao/store/notificationService/navigator/repo；
 * navigator.pendingMeetupArrival 与 dao.observeActiveForConversation 用真 MutableStateFlow（构造前注入·否则 StateFlow
 * 在构造时拿到的是 relaxed 空流）；arriveOffline 用计数 spy；scope=Unconfined 让 launch 的 mutate→rescheduleAll、
 * init 收信号同步真跑。
 * 覆盖：observe 委托 dao、倒数小条/赴约按钮两态 StateFlow、答应/婉拒/改期/变更应用(均刷到点通知)、保留(不刷)、
 * 主动约见(取会话→startManual→刷)、取消、到点赴约委托 offline 回调、到点信号本会话→赴约+清信号/他会话→不动、detectionTrigger 暴露。
 */
class ChatMeetingControllerTest {

    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var coordinator: MeetingProposalCoordinator
    private lateinit var meetingAppointmentDao: MeetingAppointmentDao
    private lateinit var meetingAppointmentStore: MeetingAppointmentStore
    private lateinit var meetupNotificationService: MeetupNotificationService
    private lateinit var notificationNavigator: NotificationNavigator
    private lateinit var contextLog: ContextLogService
    private lateinit var pendingArrivalFlow: MutableStateFlow<MeetupArrivalTarget?>
    private lateinit var activeApptsFlow: MutableStateFlow<List<MeetingAppointmentEntity>>
    private lateinit var scope: CoroutineScope
    private lateinit var controller: ChatMeetingController
    private var arriveOfflineCount = 0
    private var lastArriveUuid: String? = null

    @Before
    fun setUp() {
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        meetingAppointmentDao = mockk(relaxed = true)
        meetingAppointmentStore = mockk(relaxed = true)
        meetupNotificationService = mockk(relaxed = true)
        notificationNavigator = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        pendingArrivalFlow = MutableStateFlow(null)
        activeApptsFlow = MutableStateFlow(emptyList())
        // 构造前注入真流：StateFlow 在协作者构造时即调 observeActiveForConversation / 读 pendingMeetupArrival。
        every { notificationNavigator.pendingMeetupArrival } returns pendingArrivalFlow
        every { meetingAppointmentDao.observeActiveForConversation("conv-1") } returns activeApptsFlow
        arriveOfflineCount = 0
        lastArriveUuid = null
        scope = CoroutineScope(Dispatchers.Unconfined)
        controller = ChatMeetingController(
            scope = scope,
            conversationUuid = "conv-1",
            conversationRepo = conversationRepo,
            messageRepo = messageRepo,
            meetingProposalCoordinator = coordinator,
            meetingAppointmentDao = meetingAppointmentDao,
            meetingAppointmentStore = meetingAppointmentStore,
            meetupNotificationService = meetupNotificationService,
            notificationNavigator = notificationNavigator,
            contextLog = contextLog,
            arriveOffline = { uuid -> arriveOfflineCount++; lastArriveUuid = uuid },
        )
    }

    private fun appt(
        scheduledAt: Long,
        status: String = "confirmed",
        granularity: String = "exact",
    ) = MeetingAppointmentEntity(
        uuid = "appt-1",
        conversationUuid = "conv-1",
        status = status,
        scheduledAt = scheduledAt,
        timeGranularity = granularity,
        location = "咖啡馆",
        activity = "喝咖啡",
        hiddenTensionSeed = "有点紧张",
    )

    // ---- observe + 两态 StateFlow ----

    @Test
    fun observe约定_直接委托dao的observeByUuid() {
        val flow = MutableStateFlow<MeetingAppointmentEntity?>(null)
        every { meetingAppointmentDao.observeByUuid("appt-9") } returns flow
        assertSame(flow, controller.observeAppointment("appt-9"))
    }

    @Test
    fun 倒数小条_确认且未到点_暴露该约定() {
        val emissions = mutableListOf<MeetingAppointmentEntity?>()
        val job = scope.launch { controller.nextCountdownAppointment.collect { emissions.add(it) } }
        activeApptsFlow.value = listOf(appt(scheduledAt = System.currentTimeMillis() + 3600_000L)) // 1h 后·confirmed → countdown
        assertEquals("appt-1", emissions.last()?.uuid)
        job.cancel()
    }

    @Test
    fun 倒数小条_已到点_不暴露交赴约态() {
        val emissions = mutableListOf<MeetingAppointmentEntity?>()
        val job = scope.launch { controller.nextCountdownAppointment.collect { emissions.add(it) } }
        activeApptsFlow.value = listOf(appt(scheduledAt = System.currentTimeMillis() - 60_000L)) // 已到点 → 非 countdown
        assertNull(emissions.last())
        job.cancel()
    }

    @Test
    fun 赴约按钮_确认且到点宽限内_暴露该约定() {
        val emissions = mutableListOf<MeetingAppointmentEntity?>()
        val job = scope.launch { controller.arrivalAppointment.collect { emissions.add(it) } }
        activeApptsFlow.value = listOf(appt(scheduledAt = System.currentTimeMillis() - 60_000L)) // 1min 前·exact 3h 窗内 → arrival
        assertEquals("appt-1", emissions.last()?.uuid)
        job.cancel()
    }

    @Test
    fun 赴约按钮_未到点_不暴露() {
        val emissions = mutableListOf<MeetingAppointmentEntity?>()
        val job = scope.launch { controller.arrivalAppointment.collect { emissions.add(it) } }
        activeApptsFlow.value = listOf(appt(scheduledAt = System.currentTimeMillis() + 3600_000L)) // 未到点 → 非 arrival
        assertNull(emissions.last())
        job.cancel()
    }

    // ---- 写真理源动作（动作后统一刷到点通知）----

    @Test
    fun 答应_respondFromCardAccepted并刷到点通知() {
        controller.acceptAppointment("appt-1")
        coVerify { coordinator.respondFromCard("appt-1", accepted = true, any()) }
        coVerify { meetupNotificationService.rescheduleAll(any()) }
    }

    @Test
    fun 先不约_respondFromCardDeclined并刷到点通知() {
        controller.declineAppointment("appt-1")
        coVerify { coordinator.respondFromCard("appt-1", accepted = false, any()) }
        coVerify { meetupNotificationService.rescheduleAll(any()) }
    }

    @Test
    fun 主动约见_取会话characterUuid后startManual并刷到点通知() {
        val convo = mockk<ConversationEntity>()
        every { convo.characterUuid } returns "char-1"
        coEvery { conversationRepo.get("conv-1") } returns convo
        controller.startFutureMeeting(5_000L, MeetingTimeGranularity.EXACT, "公园", "散步")
        coVerify {
            coordinator.startManual(
                characterUuid = "char-1",
                conversationUuid = "conv-1",
                scheduledAtMillis = 5_000L,
                granularity = MeetingTimeGranularity.EXACT,
                location = "公园",
                activity = "散步",
                zone = any(),
                nowMillis = any(),
            )
        }
        coVerify { meetupNotificationService.rescheduleAll(any()) }
    }

    @Test
    fun 主动约见_会话不存在_不startManual不刷() {
        coEvery { conversationRepo.get("conv-1") } returns null
        controller.startFutureMeeting(5_000L, MeetingTimeGranularity.EXACT, "公园", "散步")
        coVerify(exactly = 0) { coordinator.startManual(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { meetupNotificationService.rescheduleAll(any()) }
    }

    @Test
    fun 换时间_rescheduleTo并刷到点通知() {
        controller.rescheduleAppointment("appt-1", 9_000L, MeetingTimeGranularity.DAY_ONLY)
        coVerify { coordinator.rescheduleTo("appt-1", 9_000L, MeetingTimeGranularity.DAY_ONLY, any()) }
        coVerify { meetupNotificationService.rescheduleAll(any()) }
    }

    @Test
    fun 应用变更_applyChangeFromCard并刷到点通知() {
        controller.applyMeetingChange("msg-1")
        coVerify { coordinator.applyChangeFromCard("msg-1", any()) }
        coVerify { meetupNotificationService.rescheduleAll(any()) }
    }

    @Test
    fun 保留原约定_keepChangeFromCard且不刷到点通知() {
        controller.keepMeetingChange("msg-1")
        coVerify { coordinator.keepChangeFromCard("msg-1") }
        coVerify(exactly = 0) { meetupNotificationService.rescheduleAll(any()) } // 真理源不变·无需动到点通知
    }

    @Test
    fun 取消约定_declineFromCard并刷到点通知() {
        controller.cancelAppointment("appt-1")
        coVerify { coordinator.declineFromCard("appt-1", any()) }
        coVerify { meetupNotificationService.rescheduleAll(any()) }
    }

    // ---- 到点赴约委托 + 信号收集 ----

    @Test
    fun 赴约_委托offline回调() {
        controller.arriveAtAppointment("appt-7")
        assertEquals(1, arriveOfflineCount)
        assertEquals("appt-7", lastArriveUuid)
    }

    @Test
    fun 到点信号_指向本会话_自动赴约并清信号() {
        pendingArrivalFlow.value = MeetupArrivalTarget("conv-1", "appt-3")
        assertEquals(1, arriveOfflineCount)
        assertEquals("appt-3", lastArriveUuid)
        coVerify { notificationNavigator.consumeMeetupArrival() }
    }

    @Test
    fun 到点信号_指向他会话_不赴约不清信号() {
        pendingArrivalFlow.value = MeetupArrivalTarget("conv-OTHER", "appt-3")
        assertEquals(0, arriveOfflineCount)
        coVerify(exactly = 0) { notificationNavigator.consumeMeetupArrival() }
    }

    // ---- 后台识别簇收进同一协作者 ----

    @Test
    fun detectionTrigger_由协作者拥有并暴露() {
        assertNotNull(controller.detectionTrigger)
    }
}
