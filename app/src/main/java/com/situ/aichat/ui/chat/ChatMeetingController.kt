package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.meeting.MeetingArrivalPolicy
import com.situ.aichat.meeting.MeetingProposalCoordinator
import com.situ.aichat.meeting.MeetupNotificationService
import com.situ.aichat.notification.NotificationNavigator
import com.situ.aichat.util.timeTickFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 「未来约定见面」域协作者——从 ChatViewModel 抽出（"未来约定见面" 功能簇·把 VM 顶回 800 绝对红线之上的那批），方法体字节级不变。
 * 聚整个见面域到一处：① 用户面动作（确认卡 observe + 答应/婉拒/换时间/变更应用/保留/取消、+ 菜单主动约见）；
 * ② 等待期倒数小条 ↔ 到点出发赴约按钮两条会话级 StateFlow；③ 到点通知点击自动赴约的信号收集；
 * ④ 后台识别簇 [detectionTrigger]（拥有并暴露给 AssistantTurnEngine 回合后调用）。
 * 任何写真理源的用户面动作后统一 [MeetupNotificationService.rescheduleAll] 重排到点通知。
 *
 * **到点赴约经回调注入**：[arriveOffline] = offlineController.arriveAtAppointment（赴约进沉浸的实现留在线下生命周期协作者，
 * 本协作者不碰 offline 引擎内部）。本协作者须在 VM 里**于 offlineController 声明之后、AssistantTurnEngine 之前**构造——其 [init]
 * 收集 pendingMeetupArrival 会调 [arriveAtAppointment]→[arriveOffline]（即便冷启已带 pending 内联首发也安全，对齐原 VM
 * init 在 offlineController 之后）；且 [detectionTrigger] 须先于 AssistantTurnEngine 就绪供其注入。
 */
internal class ChatMeetingController(
    private val scope: CoroutineScope,
    private val conversationUuid: String,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val meetingProposalCoordinator: MeetingProposalCoordinator,
    private val meetingAppointmentDao: MeetingAppointmentDao,
    private val meetingAppointmentStore: MeetingAppointmentStore,
    private val meetupNotificationService: MeetupNotificationService,
    private val notificationNavigator: NotificationNavigator,
    private val contextLog: ContextLogService,
    private val arriveOffline: (String) -> Unit,
) {

    /**
     * 后台分析触发·未来约定见面识别簇（骨干路·8d-3a；扫最近对话识别约定 → coordinator 入库）。本协作者拥有，
     * 经此公开给 AssistantTurnEngine（回合完成后 ingestFastPath / checkAndTrigger）——把识别也收进见面域单一协作者。
     */
    val detectionTrigger: MeetingDetectionTrigger = MeetingDetectionTrigger(
        scope = scope,
        conversationUuid = conversationUuid,
        conversationRepo = conversationRepo,
        messageRepo = messageRepo,
        meetingStore = meetingAppointmentStore,
        coordinator = meetingProposalCoordinator,
        meetupNotificationService = meetupNotificationService,
        contextLog = contextLog,
    )

    /** 确认卡气泡实时观察约定真理源 status（驱动待确认/已约定/婉拒态）。 */
    fun observeAppointment(uuid: String) = meetingAppointmentDao.observeByUuid(uuid)

    /**
     * 等待期倒数小条（Phase 9·会话级·§7 多会话错位坑按 conversationUuid 过滤）：本会话「下一个已确认未来约定」，
     * 响应式（确认/取消/改期即时刷新）。proposed（待确认卡阶段）不上小条；到点交 [arrivalAppointment] 变身按钮。
     */
    val nextCountdownAppointment: StateFlow<MeetingAppointmentEntity?> =
        combine(meetingAppointmentDao.observeActiveForConversation(conversationUuid), timeTickFlow()) { list, now ->
            list.firstOrNull { MeetingArrivalPolicy.isCountdownState(it.status, it.scheduledAt, now) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 到点「出发赴约」按钮（Phase 10·10d·过审 mockup `meetup_arrival_button_morph`）：本会话「已确认 + 已到点 +
     * 仍在宽限窗口内」的约定——倒数小条到点就地变身为此按钮（与 [nextCountdownAppointment] 互斥）。过宽限则两者
     * 皆空（交 Phase 11 爽约扫描）。会话级（§7 多会话错位坑按 conversationUuid 过滤）。
     */
    val arrivalAppointment: StateFlow<MeetingAppointmentEntity?> =
        combine(meetingAppointmentDao.observeActiveForConversation(conversationUuid), timeTickFlow()) { list, now ->
            val zone = ZoneId.systemDefault()
            list.firstOrNull {
                MeetingArrivalPolicy.isArrivalState(it.status, it.scheduledAt, MeetingTimeGranularity.fromRaw(it.timeGranularity), now, zone)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Phase 10 到点赴约：通知点击设的赴约信号若指向本会话 → 自动进沉浸 + 清信号（在 App「出发赴约」按钮走直接
        // arriveAtAppointment·不经此信号）。本 controller 在 offlineController 声明之后构造 → 即便冷启已带 pending 内联首发也安全。
        scope.launch {
            notificationNavigator.pendingMeetupArrival.collect { target ->
                if (target?.conversationUuid == conversationUuid) {
                    arriveAtAppointment(target.appointmentUuid)
                    notificationNavigator.consumeMeetupArrival()
                }
            }
        }
    }

    /**
     * 任何「未来约定见面」真理源写动作后统一重排到点通知（Phase 10·task10 收口）：协调器写完真理源 →
     * [MeetupNotificationService.rescheduleAll] 全量对账（确认→排·取消/赴约/过点→撤·改期→挪闹钟）。串行进 scope。
     */
    private fun launchMeetingMutation(mutate: suspend () -> Unit) {
        scope.launch {
            mutate()
            meetupNotificationService.rescheduleAll()
        }
    }

    /** 确认卡「答应」：proposed → confirmed（[zCODE] P1 走统一响应入口：幂等 + 同步卡回执 + 24h 兄弟作废守卫；刷到点通知）。 */
    fun acceptAppointment(uuid: String) = launchMeetingMutation { meetingProposalCoordinator.respondFromCard(uuid, accepted = true) }

    /** 确认卡「先不约」：取消约定（[zCODE] P1 同上统一入口；撤到点通知）。 */
    fun declineAppointment(uuid: String) = launchMeetingMutation { meetingProposalCoordinator.respondFromCard(uuid, accepted = false) }

    /** 「+」菜单「约见面」：用户自填将来见面，跳过确认闸门直接 confirmed（落「已约定」回执卡）+ 排到点通知。 */
    fun startFutureMeeting(scheduledAtMillis: Long, granularity: MeetingTimeGranularity, location: String, activity: String) {
        scope.launch {
            val convo = conversationRepo.get(conversationUuid) ?: return@launch
            meetingProposalCoordinator.startManual(
                characterUuid = convo.characterUuid,
                conversationUuid = conversationUuid,
                scheduledAtMillis = scheduledAtMillis,
                granularity = granularity,
                location = location,
                activity = activity,
                zone = ZoneId.systemDefault(),
            )
            meetupNotificationService.rescheduleAll()
        }
    }

    /** 确认卡「换个时间」/ 改期面板：更新时间 + 确认（刷到点通知）。 */
    fun rescheduleAppointment(uuid: String, scheduledAtMillis: Long, granularity: MeetingTimeGranularity) =
        launchMeetingMutation { meetingProposalCoordinator.rescheduleTo(uuid, scheduledAtMillis, granularity) }

    /** 变更确认卡「好，改 / 取消约定」：应用变更（识别来的 confirmed 改期/取消经此落地·决策①）+ 刷/撤到点通知。 */
    fun applyMeetingChange(messageUuid: String) =
        launchMeetingMutation { meetingProposalCoordinator.applyChangeFromCard(messageUuid) }

    /** 变更确认卡「保留 / 还是原来的」：保留原约定，仅收回执（真理源不变·无需动到点通知）。 */
    fun keepMeetingChange(messageUuid: String) {
        scope.launch { meetingProposalCoordinator.keepChangeFromCard(messageUuid) }
    }

    /** 倒数小条「取消约定」：取消该约定（confirmed 也可取消·store.cancel 守 isActive）+ 撤到点通知。 */
    fun cancelAppointment(uuid: String) =
        launchMeetingMutation { meetingProposalCoordinator.declineFromCard(uuid) }

    /** 「未来约定见面」到点赴约（Phase 10·到点通知点击 / 在 App「出发赴约」按钮共用）：宽限窗口内进沉浸 + markHonored（委托 offlineController）。 */
    fun arriveAtAppointment(appointmentUuid: String) = arriveOffline(appointmentUuid)
}
