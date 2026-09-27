package com.situ.aichat.openloop

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.proactive.ProactiveReplyDeliverer
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.prompt.MessageKindInference
import com.situ.aichat.prompt.TimeAnchorFormatter
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.sticker.StickerTagParser
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「惦记的事」到期主动消息生成器（活人感一期 P2·图纸 §3.2/§4.4）：某条 loop 到期时，TA 主动发一条惦记着对方的短消息。
 * 由 [com.situ.aichat.work.OpenLoopDueWorker] 到点驱动。独立后台路径——**绝不复用** ChatViewModel/回合引擎；
 * 分段落库 + 通知委托 [ProactiveReplyDeliverer]（与见面余温共用·普通聊天同口径分气泡·2026-07-07 用户拍板修订，
 * 原「单条落库」作废）。
 *
 * 五道守卫（任一不满足→静默返回·不发不改状态）：①通知开关；②loop 仍 open；③now−dueAt ≤ 2h（过窗留对话内兜底）；
 * ④会话非见面中；⑤沉浸模式关。全过 → LLM 生成 + 校验（失败静默放弃·loop 保持 open）→ 落库 + 通知 → 置 resolved。
 */
@Singleton
class OpenLoopDueMessenger @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val openLoopRepository: OpenLoopRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val contextLog: ContextLogService,
    private val deliverer: ProactiveReplyDeliverer,
    private val storyStateRepository: com.situ.aichat.data.repository.StoryStateRepository, // [zCODE] P2·锚点接地
) {

    /** 到点入口（worker 驱动）。守卫→生成→落库→通知→置 resolved；任何守卫/校验不过均静默、loop 保持 open。 */
    suspend fun deliver(loopUuid: String) {
        val settings = settingsRepo.getAppSettings()
        if (!settings.notificationsEnabled) return // 守卫①：走既有通知总开关（拍板）
        val loop = openLoopRepository.byUuid(loopUuid) ?: return
        if (loop.statusRaw != OpenLoopStatus.OPEN) return // 守卫②：已 resolved/expired → 静默（E4）
        val due = loop.dueAt ?: return // 无 dueAt 不该有 worker；防御
        val now = System.currentTimeMillis()
        if (now - due > OVERDUE_WINDOW_MS) return // 守卫③：过 2h 窗（迟到执行）→ 留对话内兜底（E6）
        val convo = conversationRepo.get(loop.conversationUuid) ?: return
        if (convo.isInOfflineMode) return // 守卫④：见面中（E5）
        val character = characterRepo.get(loop.characterUuid) ?: return
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return

        val text = generate(character, loop, config) ?: return // 校验不过→静默放弃·loop 保持 open（E8）
        deliverer.persistAndNotify(loop.conversationUuid, character, settings, text, TAG)
        openLoopRepository.markResolved(loop, now)
    }

    /**
     * §4.4 system 指令 + LLM 生成。校验：[MemoryService.strippingThinkingTags] 后 trim 非空、≤120 字符、
     * [StickerTagParser.stripStickerTags] 后经 [DirtyMessageDetector] 干净——不合格返回 null（静默放弃·不发模板兜底）。
     *
     * 2026-07-07 用户拍板修订（同见面余温）：指令追加 [TimeAnchorFormatter.formatCurrentMoment] 当前时刻行
     * （含日期/星期/时段词）——原版对现实时间零感知，深夜到点可能发出「早安式」问候。
     */
    private suspend fun generate(character: CharacterEntity, loop: OpenLoopEntity, config: ApiConfigValues): String? {
        val persona = character.personalityDescription.take(PERSONA_MAX)
        val styleClause = character.speakingStyle.takeIf { it.isNotBlank() }?.let { "说话风格：$it。" } ?: ""
        val system = "你是「${character.name}」。人设：$persona。$styleClause\n" +
            "你们之前聊天时提到过：${loop.content}——就是今天。" +
            "${TimeAnchorFormatter.formatCurrentMoment(Instant.now())}，发的消息要贴合这个真实时段。\n" +
            "请以角色身份主动给对方发一条 20~60 字的短消息，自然地表达你记得这件事、惦记着对方（比如祝好运、问问进展、或轻轻提一句）。像平时发微信那样说话。只输出消息正文，不要引号、不要任何标签或旁白。" +
            // [zCODE] P2·切片二：锚点接地（与余温同款·fail-open 无锚点省略）
            com.situ.aichat.proactive.ProactiveAnchorGrounding.groundingFor(storyStateRepository, character.uuid, character.name)
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = "请发这条消息。"),
        )
        val raw = contextLog.completion(
            source = LogSource.OPEN_LOOP_MESSAGE,
            characterName = character.name,
            config = config,
            messages = messages,
            temperature = TEMPERATURE,
        )
        val text = MemoryService.strippingThinkingTags(raw).trim()
        if (text.isEmpty() || text.length > MAX_LEN) return null
        val stripped = StickerTagParser.stripStickerTags(text)
        val kind = MessageKindInference.forAssistantText(text, isOfflineMode = false)
        if (DirtyMessageDetector.isDirty(stripped, kind)) return null
        return text
    }

    private companion object {
        private const val TAG = "OpenLoopDueMessenger"
        private const val MAX_LEN = 120
        private const val PERSONA_MAX = 200
        private const val TEMPERATURE = 0.7
        private const val OVERDUE_WINDOW_MS = 2L * 60 * 60 * 1000 // 2h
    }
}
