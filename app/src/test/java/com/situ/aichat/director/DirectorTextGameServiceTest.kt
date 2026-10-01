package com.situ.aichat.director

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.StoryStateRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.AnchorBlockParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] B8 手动档金样（中继2·拍板D）：prose 双侧落点（host=亲见界面证据·背景板同落 + 其余 cast system 镜像）/
 * 回合记账 dir-m:{sessionId}:{turnIndex}（拍板A·被点名 cast）/入场与背景板模式指令差 / 船位变更才广播 /
 * LLM 空响应零写入。记忆捕获一律 answers+firstArg（惯例⑥）。
 */
class DirectorTextGameServiceTest {

    private val contextLog = mockk<ContextLogService>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val messageRepo = mockk<MessageRepository>(relaxed = true)
    private val storyRepo = mockk<StoryStateRepository>(relaxed = true)
    private val service = DirectorTextGameService(contextLog, characterDao, conversationDao, messageRepo, storyRepo)

    private val config = mockk<ApiConfigValues>(relaxed = true)
    private val now = 1_800_000_000_000L
    private val prose = "贝拉与艾斯在码头上对峙，剑拔弩张。最终贝拉先收了刀，两人各自转身。\n[场景：码头·夜]"

    private fun char(uuid: String, name: String) = CharacterEntity(uuid = uuid, name = name, creationDate = 0L)
    private fun convo(uuid: String, charUuid: String) = ConversationEntity(uuid = uuid, title = "t", characterUuid = charUuid, creationDate = 0L)

    private fun request(mode: DirectorTextGameService.Mode, userInput: String? = null) = DirectorTextGameService.TurnRequest(
        sessionId = "s1", turnIndex = 0, mode = mode,
        castUuids = listOf("ace", "bella"), hostConversationUuid = "host-conv",
        userName = "阿丽娅", sceneSeed = "香波地码头·夜", userInput = userInput,
    )

    /** 捕获式桩（惯例⑥）：LLM 返回 prose + 消息/提示词落袋断言。 */
    private fun stubLlm(proseOut: String): MutableList<ChatMessageDto> {
        val seen = mutableListOf<ChatMessageDto>()
        coEvery {
            contextLog.streamedCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val msgs: List<ChatMessageDto> = arg(3)
            msgs.forEach { seen.add(it) }
            proseOut
        }
        return seen
    }

    private fun stubWorld() {
        coEvery { characterDao.getByUuid("ace") } returns char("ace", "艾斯")
        coEvery { characterDao.getByUuid("bella") } returns char("bella", "贝拉")
        coEvery { conversationDao.latestActiveForCharacter("ace") } returns convo("host-conv", "ace")
        coEvery { conversationDao.latestActiveForCharacter("bella") } returns convo("bella-conv", "bella")
    }

    @Test fun advance_turn_lands_both_sides_and_ledgers_mentioned_cast() = runTest {
        stubWorld()
        stubLlm(prose)
        coEvery { storyRepo.currentAnchorFor(any()) } returns null // 无锚点=位置不明行
        val saved = mutableListOf<MessageEntity>()
        coEvery { messageRepo.upsert(any()) } answers { saved.add(firstArg()); Unit }

        val out = service.advanceTurn(request(DirectorTextGameService.Mode.BACKGROUND), config, now)

        assertEquals(prose, out)
        assertEquals("双侧落点：host（assistant·背景板同落=亲见界面证据）+ bella 镜像（system 事件卡）", 2, saved.size)
        val host = saved.first { it.conversationUuid == "host-conv" }
        assertEquals("assistant", host.roleRaw)
        assertEquals(MessageKind.PLAIN_TEXT.raw, host.messageKindRaw)
        assertEquals(prose, host.content)
        val mirror = saved.first { it.conversationUuid == "bella-conv" }
        assertEquals("system", mirror.roleRaw)
        assertEquals(MessageKind.SYSTEM_EVENT_CARD.raw, mirror.messageKindRaw)
        // 拍板A：回合记账 dir-m:{sessionId}:{turnIndex}·被点名 cast（艾斯/贝拉均在 prose 中）
        coVerify(exactly = 1) {
            storyRepo.applyDirectorEvent(listOf("ace", "bella"), any(), any(), any(), "dir-m:s1:0", any(), any())
        }
    }

    @Test fun background_mode_prompt_carries_bystander_rule() = runTest {
        stubWorld()
        val seen = stubLlm(prose)
        coEvery { storyRepo.currentAnchorFor(any()) } returns null

        service.advanceTurn(request(DirectorTextGameService.Mode.BACKGROUND), config, now)

        val system = seen.first { it.role == "system" }.content.orEmpty()
        assertTrue("背景板=旁观不出声", system.contains("背景板") && system.contains("不出声"))
        assertTrue("{{user}} 在场但不参与", system.contains("在场") && system.contains("阿丽娅"))
        val user = seen.first { it.role == "user" }.content.orEmpty()
        assertTrue("背景板无人输入", user.contains("无人输入"))
    }

    @Test fun participant_mode_carries_user_input() = runTest {
        stubWorld()
        val seen = stubLlm(prose)
        coEvery { storyRepo.currentAnchorFor(any()) } returns null

        service.advanceTurn(request(DirectorTextGameService.Mode.PARTICIPANT, userInput = "拔刀挡在两人之间"), config, now)

        val system = seen.first { it.role == "system" }.content.orEmpty()
        assertTrue(system.contains("入场") && system.contains("参与者"))
        val user = seen.first { it.role == "user" }.content.orEmpty()
        assertTrue("入场模式携带 {{user}} 行动", user.contains("拔刀挡在两人之间"))
    }

    @Test fun broadcast_only_when_fleet_layer_changed() = runTest {
        stubWorld()
        stubLlm(prose)
        val pre = com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity(
            characterUuid = "ace", locationRaw = "甲板", effectiveAt = now, capturedAt = now,
            fleetLayerJson = AnchorBlockParser.FleetLayerCodec.encode(
                AnchorBlockParser.FleetLayer("白团", "新世界", "香波地", "莫比迪克号", "停靠"),
            ),
        )
        val post = pre.copy(
            fleetLayerJson = AnchorBlockParser.FleetLayerCodec.encode(
                AnchorBlockParser.FleetLayer("白团", "新世界", "某海域", "莫比迪克号", "航行中"),
            ),
        )
        // 调用序：preAnchors(1)+prompt(1)→pre；船位比对(第3次起)→post
        var calls = 0
        coEvery { storyRepo.currentAnchorFor("ace") } answers { if (calls++ < 2) pre else post }
        coEvery { storyRepo.currentAnchorFor("bella") } returns null

        service.advanceTurn(request(DirectorTextGameService.Mode.PARTICIPANT), config, now)
        coVerify(exactly = 1) { storyRepo.broadcastToFleetMates(post, now) }
    }

    @Test fun no_broadcast_when_fleet_layer_unchanged() = runTest {
        stubWorld()
        stubLlm(prose)
        val same = com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity(
            characterUuid = "ace", locationRaw = "甲板", effectiveAt = now, capturedAt = now,
            fleetLayerJson = AnchorBlockParser.FleetLayerCodec.encode(
                AnchorBlockParser.FleetLayer("白团", "新世界", "香波地", "莫比迪克号", "停靠"),
            ),
        )
        coEvery { storyRepo.currentAnchorFor(any()) } returns same

        service.advanceTurn(request(DirectorTextGameService.Mode.PARTICIPANT), config, now)
        coVerify(exactly = 0) { storyRepo.broadcastToFleetMates(any(), any()) }
    }

    @Test fun empty_llm_response_writes_nothing() = runTest {
        stubWorld()
        stubLlm("   ")
        val saved = mutableListOf<MessageEntity>()
        coEvery { messageRepo.upsert(any()) } answers { saved.add(firstArg()); Unit }

        val out = service.advanceTurn(request(DirectorTextGameService.Mode.BACKGROUND), config, now)

        assertEquals("", out)
        assertTrue("空响应零写入", saved.isEmpty())
        coVerify(exactly = 0) { storyRepo.applyDirectorEvent(any(), any(), any(), any(), any(), any(), any()) }
    }
}
