package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.StoryDao
import com.situ.aichat.data.local.entity.CharacterEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * CharacterDeletionCleaner 行为测试（T2·MockK）：R6#3 新增的第 ⑩ 步——删角色清理链必须把
 * story_character_roles 的悬空引用摘链（detach），否则该角色在进行中故事里的人设/声线注入静默丢失。
 * 全依赖 relaxed mock（清理链其余步骤各有专项覆盖，此处只钉第 ⑩ 步真的在链上、且带对 uuid）。
 */
class CharacterDeletionCleanerTest {

    @Test
    fun 删角色清理链会把故事角色摘链() = runBlocking {
        val storyDao = mockk<StoryDao>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        coEvery { settingsRepository.getAppSettings() } returns mockk(relaxed = true) {
            every { characterPromptModulesJSON } returns "{}"
        }
        val cleaner = CharacterDeletionCleaner(
            momentDao = mockk(relaxed = true),
            diaryDao = mockk(relaxed = true),
            conversationDao = mockk(relaxed = true),
            conversationMediaCleaner = mockk(relaxed = true),
            currencyDao = mockk(relaxed = true),
            giftDao = mockk(relaxed = true),
            notificationTemplateDao = mockk(relaxed = true),
            notificationDeliveryDao = mockk(relaxed = true),
            notificationWindowStatsDao = mockk(relaxed = true),
            notificationScheduler = mockk(relaxed = true),
            petReminderScheduler = mockk(relaxed = true),
            redPacketExpirationScanService = mockk(relaxed = true),
            momentNotificationPurger = mockk(relaxed = true),
            milestoneCelebrationNotifier = mockk(relaxed = true),
            meetingAppointmentStore = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            storyDao = storyDao,
            storyStateRepository = mockk(relaxed = true), // [zCODE] 点3 追加项A
        )
        val character = mockk<CharacterEntity>(relaxed = true)
        every { character.uuid } returns "char-1"

        cleaner.cleanup(character)

        coVerify(exactly = 1) { storyDao.detachCharacterFromRoles("char-1") }
    }
}
