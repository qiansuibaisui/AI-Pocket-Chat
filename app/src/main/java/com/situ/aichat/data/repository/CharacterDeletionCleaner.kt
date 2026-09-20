package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.NotificationWindowStatsDao
import com.situ.aichat.data.local.dao.StoryDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.moments.MomentNotificationPurger
import com.situ.aichat.notification.NotificationScheduler
import com.situ.aichat.pet.PetReminderScheduler
import com.situ.aichat.prompt.PromptModuleService
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.relationship.MilestoneCelebrationNotifier
import com.situ.aichat.util.ContentImageStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删除角色前清理其散落的关联数据（1:1 iOS `MomentCleanupService.deleteCharacterMoments` +
 * `removeSystemNotifications`，在 ContactListView 删角色时整套执行）。
 *
 * **为什么需要**：Room FK CASCADE 只覆盖对 [CharacterEntity] 建了外键的表（会话/消息/里程碑/宠物/钱包/
 * 日程）。朋友圈帖/评论/赞、日记评论、通知台账对角色**无外键**，删角色后会孤儿残留——其中朋友圈帖会永久
 * 留在主 feed（feed 查询不按角色存在性过滤，30 天硬删只清软删的）。故删角色前必须显式清理。
 *
 * **调用约束**：必须在 [CharacterRepository.delete] **之前**调用（iOS 顺序：先清理散落数据，后删角色本体）。
 *
 * 覆盖（对齐 iOS）：朋友圈点赞/评论/帖 + 日记评论 + 通知数据(模板/投递/窗口统计) + 通知调度状态(闹钟/快照/
 * 随机标记) + 提示词模块角色覆盖 + 每角色通知开关 + 角色侧金币流水(⑧) + 礼物记录及 DIY 图(⑨·R6) +
 * 故事角色摘链(⑩·R6#3)。日程由 FK CASCADE 处理。
 */
@Singleton
class CharacterDeletionCleaner @Inject constructor(
    private val momentDao: MomentDao,
    private val diaryDao: DiaryDao,
    private val conversationDao: ConversationDao,
    private val conversationMediaCleaner: ConversationMediaCleaner,
    private val currencyDao: CurrencyDao,
    private val giftDao: GiftDao,
    private val storyStateRepository: StoryStateRepository, // [zCODE] P1·第3项 追加项A：锚点三表级联
    private val notificationTemplateDao: NotificationTemplateDao,
    private val notificationDeliveryDao: NotificationDeliveryDao,
    private val notificationWindowStatsDao: NotificationWindowStatsDao,
    private val notificationScheduler: NotificationScheduler,
    private val petReminderScheduler: PetReminderScheduler,
    private val redPacketExpirationScanService: RedPacketExpirationScanService,
    private val momentNotificationPurger: MomentNotificationPurger,
    private val milestoneCelebrationNotifier: MilestoneCelebrationNotifier,
    private val meetingAppointmentStore: MeetingAppointmentStore,
    private val settingsRepository: SettingsRepository,
    private val storyDao: StoryDao,
) {

    /** 清理角色 [character] 的全部散落关联数据。须在 [CharacterRepository.delete] 之前调用。 */
    suspend fun cleanup(character: CharacterEntity) {
        val uuid = character.uuid

        // ⓪ P1-44 预捕获：朋友圈通知族 id 枚举源（自有帖 + 赞/评过的帖）必须在 ① 删行前取，删后查询=空集整路落空。
        val ownPostUuids = momentDao.postUuidsByCharacter(uuid)
        val interactedPostUuids = momentDao.interactedPostUuidsByCharacter(uuid)

        // ⓪b 撤朋友圈/里程碑已弹通知（批8 复核修 cancel-before-delete）：两专线零 DB 依赖、cancel 幂等——
        //     先撤再删，则 ①-⑧ 任一步失败重试时「行仍在可重捕获 or 已撤干净」，残影不可能存活
        //     （原 ④d/④e 在 ① 之后：中途失败重试时 ⓪ 查询已空集=永久残影）。代价=删除失败角色存活时
        //     通知被提前撤掉，良性。合并新动态（moment_newpost_merged）有意豁免。
        momentNotificationPurger.purgeForCharacter(uuid, ownPostUuids, interactedPostUuids)
        // 里程碑（P1-44 拍板=单槽）：固定共享 id 盲撤会误伤他角色最新庆祝 → 槽位==被删角色才撤并清槽。
        milestoneCelebrationNotifier.purgeForCharacter(uuid)

        // ① 朋友圈（1:1 iOS 顺序）：先删该角色散落在所有帖（含他人帖）下的点赞 → 评论，再删本人帖
        //    （本人帖下他人的评论/点赞由 moment_comment/moment_like → moment_post 的 FK CASCADE 带走）。
        momentDao.deleteLikesByCharacter(uuid)
        momentDao.deleteCommentsByCharacter(uuid)
        momentDao.deletePostsByCharacter(uuid)

        // ② 日记评论（含散落在他人日记下的）+ 日记点赞（R3 diary_reactions·同口径清理）。
        diaryDao.deleteCommentsByCharacter(uuid)
        diaryDao.deleteReactionsByCharacter(uuid)

        // 会话列表取一次（④ 撤忙碌回复已弹通知按会话 uuid 枚举；⑦ 媒体清理复用，省一次查询）。
        val conversations = conversationDao.getByCharacter(uuid)

        // ④ 通知调度状态：撤销待发闹钟/WorkManager + 清调度 registry/快照/随机标记 + **撤已弹通知**（P1-25，
        //    = iOS removeSystemNotifications 全量 + cleanupNotificationData 的 UserDefaults 清理）。
        //    ⚠️ P1-25 顺序契约：必须先于 ③ 删台账——purge 读台账 requestIdentifier 撤日历已弹通知/孤儿闹钟
        //    （日历 key 不含 characterId），台账先删则该路静默失效（NotificationScheduler.purgeCharacterState 互指）。
        notificationScheduler.purgeCharacterState(character, conversations.map { it.uuid })

        // ④b 宠物饿/病提醒（13.7c 复核 MED + P1-25 扩已弹）：宠物闹钟 key 前缀 aichat_pet_ 不在 ④ 的调度 registry 内、
        //     删角色后宠物行 CASCADE 消失，rescheduleAll 再也够不着 → 必须在此显式撤（闹钟+已弹通知）。
        petReminderScheduler.purgeForCharacter(uuid)

        // ④c 红包 22h 预警（批7 复核修）：key=recordUuid 不落台账不含 characterId，④ 三路枚举够不着 →
        //     红包模块专线按会话撤「闹钟+已弹通知」（=iOS conversationUUID 路对红包的双撤）。
        redPacketExpirationScanService.purgeForConversations(conversations.map { it.uuid })

        // ④d Phase 10 未来约定见面：撤该角色全部约定的到点通知 + 删约定行（无 FK 不级联·key=meetup_<uuid> 不含
        //     characterId、④ 三路够不着 → 约定模块自有清理：先枚举 uuid 撤通知、再删行，防孤儿到点错喊赴约）。
        meetingAppointmentStore.deleteForCharacter(uuid)

        // [zCODE] P1·第3项 追加项A：锚点中央登记三表级联（快照/账本孤儿行 + 船团成员关系——不删则广播扇出
        // 会持续命中幽灵卡、名片锚点行读到已删卡残留）。
        storyStateRepository.deleteAllForCharacter(uuid)


        // ③ 通知台账（模板/投递记录/窗口统计）= iOS cleanupNotificationData 的 DB 部分。必须在 ④ 之后（见上）。
        notificationTemplateDao.deleteForCharacter(uuid)
        notificationDeliveryDao.deleteForCharacter(uuid)
        notificationWindowStatsDao.deleteForCharacter(uuid)

        // ⑤ 提示词模块的角色专属覆盖（回到全局）= iOS cleanupPromptModuleOverrides。
        val currentModulesJson = settingsRepository.getAppSettings().characterPromptModulesJSON
        val updatedModulesJson = PromptModuleService.removeCharacterOverride(uuid, currentModulesJson)
        if (updatedModulesJson != currentModulesJson) {
            settingsRepository.setCharacterPromptModulesJSON(updatedModulesJson)
        }

        // ⑥ 每角色通知开关（= iOS 删 CharacterStreakNotificationPreference → 回落默认开）：
        //    从「已关闭」集合移除该角色（不在集合内则为幂等 no-op）。
        settingsRepository.setCharacterNotificationEnabled(uuid, enabled = true)

        // ⑦ 消息/语音磁盘媒体（14.7c 堵漏）：FK CASCADE 删角色只清会话/消息库行、**不删磁盘文件**
        //    （音频/图片/缩略图落 filesDir）。必须在删角色（CASCADE 抹消息）之前，逐会话经 [ConversationMediaCleaner]
        //    清媒体，否则文件永久孤儿。复用删会话同一清理器（DRY）；与单删的即时回收（[MessageRepository.deleteByUuid]）
        //    共同覆盖全部消息媒体泄漏路径，故安卓无需 iOS 的周期 cleanupOrphanedFiles 全局扫（且全局扫在共享目录上有误删风险）。
        for (conversation in conversations) {
            conversationMediaCleaner.cleanup(conversation.uuid)
        }

        // ⑧ 角色侧金币流水（14.7d，1:1 iOS cleanupOrphanedRecords 对 CurrencyTransaction ownerType==.character）：
        //    CharacterWallet 有 FK CASCADE 自走，但流水字符串关联无外键 → 不删则永久孤儿堆积（仅 DB 膨胀，不碰用户钱包）。
        //    仅删该角色侧流水，不改任何余额=零钱算。
        currencyDao.deleteCharacterTransactions(uuid)

        // ⑨ 礼物记录（R6，1:1 iOS cleanupOrphanedRecords 对 GiftRecord）：gift_records 对角色无外键 →
        //    不删则收礼盒（[com.situ.aichat.ui.gift.GiftBoxScreen]）永久残留「未知」对手方条目 + DB 行无界堆积 +
        //    DIY 礼物图片永久孤儿。先查 DIY 图盘路径删文件（删行后无从取得），再删该角色作为送/收方的全部礼物行。
        //    纯历史删除，**不动钱包余额**=零钱算（与 ⑧ 同档）。
        val diyImagePaths = giftDao.diyImagePathsForCharacter(uuid)
        diyImagePaths.forEach { ContentImageStore.delete(it) }
        giftDao.deleteForCharacter(uuid)

        // ⑩ 故事角色摘链（R6#3，= iOS cleanupStoryRoles 语义）：story_character_roles 对角色无外键，
        //    删角色后 characterId 悬空 → 角色段采集查不到人、人设/声线注入静默丢失。置 null 降级为
        //    「纯故事角色」（roleName/roleDescription 仍在，生成管线本就支持该形态），故事不断更。
        storyDao.detachCharacterFromRoles(uuid)
    }
}
