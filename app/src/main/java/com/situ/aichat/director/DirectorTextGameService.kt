package com.situ.aichat.director

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.StoryStateRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.AnchorBlockParser
import com.situ.aichat.prompt.AnchorInjectionBuilder
import com.situ.aichat.prompt.AnchorVocabulary
import com.situ.aichat.prompt.memory.MemoryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [zCODE] B8 手动档=文游引擎（中继2·终审拍板 2026-10-01）。入口挂导演通道（群聊未建·UI 归 P6 管理台——本批只做管道）。
 *
 * 每回合（[advanceTurn]）= 一次导演推进：装配导演 prompt → LLM 整段 prose → 落点双侧（拍板D）：
 * - **host 会话**（用户看着剧情推进的会话=「亲见」的界面证据·背景板模式同样落）→ assistant 消息；
 * - **其余 cast 会话**镜像 → system 事件卡消息（其记忆/上下文自然携带）；
 * → prose 内锚点行经 [AnchorBlockParser] 解析 → 剧中被点名的 cast 经 [StoryStateRepository.applyDirectorEvent]
 * 通道②记账（eventKey=`dir-m:{sessionId}:{turnIdx}`·拍板A）→ 船位变更（fleetLayer 海域/状态变化）才补调
 * [StoryStateRepository.broadcastToFleetMates]（B9 相遇不广播同源纪律）。
 *
 * 两个子档输出形态相同（整段 prose），差异仅 {{user}} 参与（入场=参与者可交互推进；背景板=在场旁观不说话不反应）。
 */
@Singleton
class DirectorTextGameService @Inject constructor(
    private val contextLog: ContextLogService,
    private val characterDao: CharacterDao,
    private val conversationDao: ConversationDao,
    private val messageRepo: MessageRepository,
    private val storyRepo: StoryStateRepository,
) {

    /** 子档（入场={{user}} 参与者；背景板={{user}} 在场旁观·亲见未参与）。 */
    enum class Mode { PARTICIPANT, BACKGROUND }

    /** 单回合请求（会话生命周期归调用方/P6；本服务回合级无状态——记账即时落库，进程死亡不丢账）。 */
    data class TurnRequest(
        val sessionId: String,
        val turnIndex: Int,
        val mode: Mode,
        val castUuids: List<String>,
        val hostConversationUuid: String,
        val userName: String = "用户",
        val sceneSeed: String = "",
        val userInput: String? = null, // 入场模式=本轮 {{user}} 行动；背景板恒 null
    )

    /** 推进一回合，返回整段 prose（已剥 think）。LLM 空/异常 → 空串（调用侧重试/提示）。 */
    suspend fun advanceTurn(
        request: TurnRequest,
        config: ApiConfigValues,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = withContext(Dispatchers.IO) {
        val cast = request.castUuids.mapNotNull { runCatching { characterDao.getByUuid(it) }.getOrNull() }
        if (cast.isEmpty()) return@withContext ""
        // 船位变更判定基准（回合前锚点）
        val preAnchors = cast.associate { it.uuid to runCatching { storyRepo.currentAnchorFor(it.uuid) }.getOrNull() }

        val messages = buildMessages(request, cast)
        var prose = ""
        for (attempt in 1..2) {
            val buffer = contextLog.streamedCompletion(
                source = LogSource.DIRECTOR_TEXT_GAME,
                characterName = cast.first().name,
                config = config,
                messages = messages,
                temperature = 0.9,
            )
            val candidate = MemoryService.strippingThinkingTags(buffer)
            if (candidate.isNotBlank()) { prose = candidate; break }
            if (attempt < 2) kotlinx.coroutines.delay(200)
        }
        if (prose.isBlank()) return@withContext ""

        // 拍板D 双侧落点：host=亲见界面证据（背景板同落）；其余 cast=system 事件卡镜像
        runCatching {
            messageRepo.upsert(
                MessageEntity(
                    messageUUID = UUID.randomUUID().toString(),
                    conversationUuid = request.hostConversationUuid,
                    roleRaw = "assistant",
                    content = prose,
                    timestamp = nowMillis,
                    messageKindRaw = MessageKind.PLAIN_TEXT.raw,
                ),
            )
        }.onFailure { Log.w(TAG, "文游 host 消息落库失败: ${it.message}") }
        for (c in cast) {
            val convo = runCatching { conversationDao.latestActiveForCharacter(c.uuid) }.getOrNull() ?: continue
            if (convo.uuid == request.hostConversationUuid) continue
            runCatching {
                messageRepo.upsert(
                    MessageEntity(
                        messageUUID = UUID.randomUUID().toString(),
                        conversationUuid = convo.uuid,
                        roleRaw = "system",
                        content = prose.take(120),
                        timestamp = nowMillis,
                        messageKindRaw = MessageKind.SYSTEM_EVENT_CARD.raw,
                    ),
                )
            }.onFailure { Log.w(TAG, "文游镜像落库失败 char=${c.name}: ${it.message}") }
        }

        // 锚点行解析 → 被点名 cast 通道②记账（dir-m:{sessionId}:{turnIndex}）
        val anchorBlock = AnchorBlockParser.parseLastBlock(prose)
        if (anchorBlock != null) {
            val mentioned = cast.filter { prose.contains(it.name) }
            if (mentioned.isNotEmpty()) {
                runCatching {
                    storyRepo.applyDirectorEvent(
                        characterUuids = mentioned.map { it.uuid },
                        conversationUuids = emptyMap(),
                        eventName = anchorBlock.eventName.ifBlank { "导演推进" },
                        locationRaw = anchorBlock.locationRaw,
                        eventKey = "dir-m:${request.sessionId}:${request.turnIndex}",
                        description = prose.take(60),
                        nowMillis = nowMillis,
                    )
                }.onFailure { Log.w(TAG, "文游回合记账失败: ${it.message}") }
                // 船位变更才广播（B9 不广播同源纪律；防两人相遇全团错位）
                for (c in mentioned) {
                    val fleetLayerChanged = runCatching {
                        val pre = preAnchors[c.uuid]?.let { AnchorBlockParser.FleetLayerCodec.decode(it.fleetLayerJson) }
                        val post = storyRepo.currentAnchorFor(c.uuid)
                            ?.let { AnchorBlockParser.FleetLayerCodec.decode(it.fleetLayerJson) }
                        post != null && pre != post
                    }.getOrDefault(false)
                    if (fleetLayerChanged) {
                        val latest = storyRepo.currentAnchorFor(c.uuid) ?: continue
                        runCatching { storyRepo.broadcastToFleetMates(latest, nowMillis) }
                            .onFailure { Log.w(TAG, "文游船位广播失败（不影响回合）: ${it.message}") }
                    }
                }
            }
        }
        prose
    }

    /** 导演 prompt 装配（三/四角色内小群像·管语精简；认知红线摘要自认知边界六条）。 */
    private suspend fun buildMessages(request: TurnRequest, cast: List<CharacterEntity>): List<ChatMessageDto> {
        val system = buildString {
            appendLine("你是剧情导演，负责推进一段多角色群像剧情。")
            appendLine("参与角色：")
            for (c in cast) {
                val anchor = runCatching { storyRepo.currentAnchorFor(c.uuid) }.getOrNull()
                val fleet = anchor?.let { AnchorBlockParser.FleetLayerCodec.decode(it.fleetLayerJson) }
                val fleetLine = fleet?.let { AnchorInjectionBuilder.fleetLineOf(it) }?.let { "｜$it" }.orEmpty()
                val loc = when {
                    anchor == null -> "位置不明"
                    AnchorVocabulary.freshnessOf(anchor.effectiveAt, System.currentTimeMillis()) == AnchorVocabulary.FreshnessLevel.STALE -> "位置不明"
                    else -> anchor.locationRaw.ifBlank { "船上" }
                }
                appendLine("- ${c.name}：${c.occupation.ifBlank { "海员" }}。当前：$loc$fleetLine")
            }
            appendLine(
                if (request.mode == Mode.PARTICIPANT) {
                    "模式：入场——{{user}}（${request.userName}）是事件参与者，会行动、说话、推动剧情。"
                } else {
                    "模式：背景板——{{user}}（${request.userName}）在场但全程旁观：不出声、不做反应、不被卷入，剧情照常推进。"
                },
            )
            appendLine("规则：第三人称叙述；互为陌生人的角色不得表现得熟络；不得虚构未被演出的既往。")
            appendLine("若场景或位置发生变化，回复最后一行用一行 [场景：当前地点·当前时间] 标注。")
            append("输出：一段连贯的剧情叙述（100~300字），不要标题、不要解释。")
        }
        val user = buildString {
            if (request.sceneSeed.isNotBlank()) appendLine(request.sceneSeed)
            append(
                if (request.mode == Mode.PARTICIPANT && !request.userInput.isNullOrBlank()) {
                    "{{user}}（${request.userName}）这一轮的动向：${request.userInput}"
                } else {
                    "（无人输入，请继续推进剧情）"
                },
            )
        }
        return listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )
    }

    private companion object {
        const val TAG = "DirectorTextGame"
    }
}
