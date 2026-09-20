package com.situ.aichat.data.repository

import android.util.Log
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.AnchorSource
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import com.situ.aichat.data.local.entity.StoryEventSource
import com.situ.aichat.prompt.AnchorBlockParser
import com.situ.aichat.prompt.AnchorVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * [zCODE] P1·第2项 锚点中央登记——剧情状态存储层唯一写入口（评审点1）。
 *
 * 设计原则落点：「状态由系统生成、模型只读不写」= 全 App 仅本类可写 story_anchor_snapshots / story_event_ledger
 * 两表；模型输出的锚点块经 [recordTurnAnchor] 这一个系统动作转结构化行。
 *
 * 写入三通道：
 * ① [recordTurnAnchor]（对话锚点块落库 + 无块兜底延续——换模型不断链的核心）；
 * ② [applyDirectorEvent]（P4 导演系统预留，本阶段无调用方，实现就绪）；
 * ③ [applyManualCorrection]（P3 管理台预留，同上；manual 行在 [currentAnchorFor] 读取侧享最高优先级）。
 */
@Singleton
class StoryStateRepository @Inject constructor(
    private val dao: StoryStateDao,
    private val scheduleDao: com.situ.aichat.data.local.dao.ScheduleDao, // [zCODE] 读取处5：storyLedger 星标通道
    private val messageRepo: com.situ.aichat.data.repository.MessageRepository, // [zCODE] 点3：world_sync ⚓ 通知投递
) {

    /**
     * 通道①：回合收尾落锚点（幂等·评审附加条件1）。
     *
     * @param anchor 本轮解析到的锚点块（null = 模型未输出 → 兜底延续上一快照，effectiveAt 原样携带不刷新）。
     * @param turnEndMessageUuid 本回合最后一条落库消息——幂等键（同消息 + 同通道已存在行则跳过，重试/多路径收尾零重复）。
     * @return 实际写入的行（跳过/无可延续 → null）。
     */
    suspend fun recordTurnAnchor(
        characterUuid: String,
        conversationUuid: String,
        anchor: AnchorBlockParser.AnchorBlock?,
        turnEndMessageUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): StoryAnchorSnapshotEntity? = withContext(Dispatchers.IO) {
        val source = if (anchor != null) AnchorSource.DIALOG_BLOCK else AnchorSource.DIALOG_CARRYOVER
        if (anchor == null) {
            val previous = dao.latestSnapshotFor(characterUuid) ?: return@withContext null // 从未建档 → 无可延续
            insertDeduped(
                previous.copy(
                    uuid = java.util.UUID.randomUUID().toString(),
                    conversationUuid = conversationUuid,
                    sourceRaw = source.raw,
                    // 兜底延续：effectiveAt 原样携带（时效保真·评审拍板），仅 capturedAt 前移
                    effectiveAt = previous.effectiveAt,
                    capturedAt = nowMillis,
                    relatedMessageUUID = turnEndMessageUuid,
                ),
            )
        } else {
            val normalized = AnchorVocabulary.normalize(anchor.locationRaw)
            insertDeduped(
                StoryAnchorSnapshotEntity(
                    characterUuid = characterUuid,
                    conversationUuid = conversationUuid,
                    eventName = anchor.eventName,
                    locationRaw = anchor.locationRaw,
                    locationKey = normalized.key,
                    motionStateRaw = normalized.state.raw,
                    sourceRaw = source.raw,
                    effectiveAt = nowMillis, // 模型刚输出的位置 = 本轮成立
                    capturedAt = nowMillis,
                    relatedMessageUUID = turnEndMessageUuid,
                    fleetKey = dao.fleetKeyOfCharacter(characterUuid).orEmpty(), // [zCODE] 点3：快照时船团键（无团=空）
                    presentListJson = encodePresentList(anchor.presentList), // [zCODE] 点3：在场名单（仅规范式提取）
                ),
            )
        }
    }

    /**
     * 通道②（P4 预留·实现就绪）：导演事件登记——多卡受影响时逐卡各写一行（白团靠岸 ≠ 佩罗娜靠岸），
     * 并把事件记入已完成账本（治「明天靠岸却滞留」的读取侧数据源）。
     */
    suspend fun applyDirectorEvent(
        characterUuids: List<String>,
        conversationUuids: Map<String, String>,
        eventName: String,
        locationRaw: String,
        eventKey: String,
        description: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val normalized = AnchorVocabulary.normalize(locationRaw)
        characterUuids.forEach { char ->
            insertDeduped(
                StoryAnchorSnapshotEntity(
                    characterUuid = char,
                    conversationUuid = conversationUuids[char].orEmpty(),
                    eventName = eventName,
                    locationRaw = locationRaw,
                    locationKey = normalized.key,
                    motionStateRaw = normalized.state.raw,
                    sourceRaw = AnchorSource.DIRECTOR.raw,
                    effectiveAt = nowMillis,
                    capturedAt = nowMillis,
                    relatedMessageUUID = "director-$eventKey-${nowMillis / 1000}", // 伪消息键：同秒同事件幂等
                ),
            )
        }
        recordCompletedEvent(
            characterUuids = characterUuids,
            conversationUuids = conversationUuids,
            eventKey = eventKey,
            description = description,
            source = StoryEventSource.DIRECTOR,
            nowMillis = nowMillis,
        )
    }

    /**
     * 通道③（P3 预留·实现就绪）：手动修正——写 manual 行；读取侧 [currentAnchorFor] 在同刻存在 manual 行时
     * 优先取 manual（手动=最高优先级，dialog 流不得改回）。
     */
    suspend fun applyManualCorrection(
        characterUuid: String,
        conversationUuid: String,
        eventName: String,
        locationRaw: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): StoryAnchorSnapshotEntity? {
        val normalized = AnchorVocabulary.normalize(locationRaw)
        return insertDeduped(
            StoryAnchorSnapshotEntity(
                characterUuid = characterUuid,
                conversationUuid = conversationUuid,
                eventName = eventName,
                locationRaw = locationRaw,
                locationKey = normalized.key,
                motionStateRaw = normalized.state.raw,
                sourceRaw = AnchorSource.MANUAL.raw,
                effectiveAt = nowMillis,
                capturedAt = nowMillis,
                relatedMessageUUID = "manual-$nowMillis-${characterUuid.take(8)}",
            ),
        )
    }

    /** 已完成事件登记（幂等：同角色同 eventKey 不堆行）。空/空白 eventKey 拒收。 */
    suspend fun recordCompletedEvent(
        characterUuids: List<String>,
        conversationUuids: Map<String, String>,
        eventKey: String,
        description: String,
        source: StoryEventSource = StoryEventSource.DIALOG_ACK,
        relatedMessageUUID: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val key = eventKey.trim()
        if (key.isEmpty()) return
        withContext(Dispatchers.IO) {
            characterUuids.forEach { char ->
                val ledgerUuid = java.util.UUID.randomUUID().toString()
                val rowId = dao.insertLedger(
                    StoryEventLedgerEntity(
                        uuid = ledgerUuid,
                        characterUuid = char,
                        conversationUuid = conversationUuids[char].orEmpty(),
                        eventKey = key,
                        description = description,
                        completedAt = nowMillis,
                        sourceRaw = source.raw,
                        relatedMessageUUID = relatedMessageUUID,
                    ),
                )
                // [zCODE] P1·第2项 读取处5：日程补丁星标通道（eventTypeRaw="storyLedger"·复用见面星标视觉）。
                // **幂等键边界（评审附加条件3）**：仅 OFFLINE_MEETING / DIRECTOR 级登记才插星标——dialog_ack
                // （聊天里随口确认）不插，避免日程表被闲聊塞满；常量判断集中此处。rowId == -1L = 唯一索引命中
                // （重复登记）→ 跳过插入，天然幂等；relatedMessageUUID = ledger uuid（同事件不重复插的追溯锚）。
                if (rowId != -1L && source in SCHEDULE_STAR_SOURCES) {
                    runCatching { insertScheduleStarEvent(char, description, ledgerUuid, nowMillis) }
                        .onFailure { Log.w(TAG, "storyLedger 星标插入失败（不影响登记）char=${char.take(8)}: ${it.message}") }
                }
            }
        }
    }

    /** 今日日程 get-or-create + 追加 storyLedger 星标事件（范式抄 OfflineMeetingService.recordOfflineScheduleEvent）。 */
    private suspend fun insertScheduleStarEvent(characterUuid: String, description: String, ledgerUuid: String, nowMillis: Long) {
        val zone = java.time.ZoneId.systemDefault()
        val todayStart = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val schedule = scheduleDao.scheduleFor(characterUuid, todayStart) ?: run {
            val created = com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity(
                uuid = java.util.UUID.randomUUID().toString(),
                characterUuid = characterUuid,
                date = todayStart,
            )
            scheduleDao.insertSchedule(created)
            created
        }
        val nextSortOrder = (scheduleDao.eventsForSchedule(schedule.uuid).maxOfOrNull { it.sortOrder } ?: -1) + 1
        scheduleDao.insertEvents(
            listOf(
                com.situ.aichat.data.local.entity.ScheduleEventEntity(
                    uuid = java.util.UUID.randomUUID().toString(),
                    scheduleUuid = schedule.uuid,
                    startTime = nowMillis,
                    endTime = nowMillis,
                    periodLabel = "已完成事件",
                    activity = description.take(60),
                    moodEmoji = "✓",
                    eventTypeRaw = EVENT_TYPE_STORY_LEDGER,
                    sortOrder = nextSortOrder,
                    relatedMessageUUID = ledgerUuid,
                ),
            ),
        )
    }

    // ── 读 API（评审点2 五处接入的数据源；本阶段先备好） ──

    // ── [zCODE] P1·第3项 世界锚点层：船团广播（防环）+ 统一粒度 ──

    /** 广播源白名单（防环核心）：world_sync 行落库**不再触发**再广播——A→B 后 B 不回写 A，单向扇出无回声环。 */
    private val BROADCAST_SOURCES = setOf(AnchorSource.DIALOG_BLOCK, AnchorSource.MANUAL, AnchorSource.DIRECTOR)

    /**
     * 点3·船团广播：`trigger` 为刚落库的锚点行（调用方 = [recordTurnAnchor] 收尾/Deliverer）。同团其余成员各写一行
     * `source=world_sync`（内容 = 触发行的**基线**：locationRaw/motionState 原样——广播行即纯基线，无个体前缀）；
     * 幂等键 `"{触发消息uuid}:{目标卡uuid}"`（唯一索引双保险，重复广播零行）。
     *
     * 个体冲突不覆盖：目标卡有 AGING 内个人锚点且 MotionState 与基线不同 → **跳过写行**仅记 world_sync_conflict
     * 日志计数（个体偏移优先，防"白团靠岸≠佩罗娜靠岸"被基线抹平）；⚓ 通知复用 B1 通道（成员表 conversationUuid
     * 空 = 仅写行不通知）。返回实际扇出的行数（观测用）。
     */
    suspend fun broadcastToFleetMates(
        trigger: StoryAnchorSnapshotEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        val source = AnchorSource.fromRaw(trigger.sourceRaw)
        if (source !in BROADCAST_SOURCES) return@withContext 0 // 防环：world_sync 不再广播
        val fleetKey = trigger.fleetKey.ifBlank { dao.fleetKeyOfCharacter(trigger.characterUuid) ?: return@withContext 0 }
        val mates = dao.membersOfFleet(fleetKey).filter { it.characterUuid != trigger.characterUuid }
        var fanned = 0
        for (mate in mates) {
            val idemKey = "${trigger.relatedMessageUUID}:${mate.characterUuid}"
            if (dao.snapshotExists(idemKey, AnchorSource.WORLD_SYNC.raw)) continue
            // 个体冲突守卫：目标卡 fresh(AGING) 个人锚点且状态不同 → 不覆盖，仅计数
            val own = dao.latestSnapshotFor(mate.characterUuid)
            val ownEffective = own?.takeIf {
                AnchorVocabulary.freshnessOf(it.effectiveAt, nowMillis) != AnchorVocabulary.FreshnessLevel.STALE
            }
            if (ownEffective != null && AnchorVocabulary.MotionState.fromRaw(ownEffective.motionStateRaw) !=
                AnchorVocabulary.MotionState.fromRaw(trigger.motionStateRaw)
            ) {
                Log.w(TAG, "world_sync_conflict（个体锚点优先不覆盖）mate=${mate.characterUuid.take(8)} fleet=$fleetKey")
                continue
            }
            val rowId = dao.insertSnapshot(
                trigger.copy(
                    uuid = java.util.UUID.randomUUID().toString(),
                    characterUuid = mate.characterUuid,
                    conversationUuid = mate.conversationUuid,
                    eventName = trigger.eventName,
                    fleetKey = fleetKey,
                    presentListJson = "", // 广播行不带触发卡的在场名单
                    sourceRaw = AnchorSource.WORLD_SYNC.raw,
                    effectiveAt = trigger.effectiveAt, // 基线成立时点原样（时效保真同口径）
                    capturedAt = nowMillis,
                    relatedMessageUUID = idemKey,
                ),
            )
            if (rowId != -1L) {
                fanned++
                // ⚓ 通知（复用 B1 通道）：成员有登记会话且节点确实变更 → 插轻量系统条
                if (mate.conversationUuid.isNotBlank() && own != null &&
                    AnchorVocabulary.anchorChanged(own, trigger)
                ) {
                    runCatching {
                        messageRepo.upsert(
                            com.situ.aichat.data.local.entity.MessageEntity(
                                messageUUID = java.util.UUID.randomUUID().toString(),
                                conversationUuid = mate.conversationUuid,
                                roleRaw = "system",
                                content = com.situ.aichat.data.model.SystemEventJson.encode(
                                    com.situ.aichat.data.model.makeSceneUpdateEventData(trigger.eventName, trigger.locationRaw, nowMillis),
                                ),
                                timestamp = nowMillis,
                                messageKindRaw = com.situ.aichat.data.model.MessageKind.SYSTEM_EVENT_CARD.raw,
                            ),
                        )
                    }.onFailure { Log.w(TAG, "world_sync ⚓ 通知失败（不影响广播）: ${it.message}") }
                }
            }
        }
        fanned
    }

    /** 统一粒度模式（裁决5）：默认船团基线+个体偏移；ABSOLUTE_UNITY = 团基线绝对统一（world_sync 行即纯基线）。 */
    enum class UnityMode { BASELINE_PLUS_OFFSET, ABSOLUTE_UNITY }

    /**
     * 点3·统一粒度读口：`effectiveLocation = fleetBaseline ∪ personalOffset`。
     * - BASELINE_PLUS_OFFSET（默认）：返回该卡最新行（个人/dialog/world_sync 均可）——个人行天然含偏移；
     * - ABSOLUTE_UNITY：返回该团最近一次 world_sync/manual 基线行（无个人前缀·无团返回 null fail-open）。
     */
    suspend fun effectiveAnchorFor(
        characterUuid: String,
        unity: UnityMode = UnityMode.BASELINE_PLUS_OFFSET,
    ): StoryAnchorSnapshotEntity? = withContext(Dispatchers.IO) {
        when (unity) {
            UnityMode.BASELINE_PLUS_OFFSET -> dao.latestSnapshotFor(characterUuid)
            UnityMode.ABSOLUTE_UNITY -> {
                val fleet = dao.fleetKeyOfCharacter(characterUuid) ?: return@withContext null
                latestBaselineOfFleet(fleet)
            }
        }
    }

    /** 团内最近一次基线行（world_sync 优先，manual/director 兜底——dialog_block 是个体行不算团基线）。 */
    private suspend fun latestBaselineOfFleet(fleetKey: String): StoryAnchorSnapshotEntity? {
        val mates = dao.membersOfFleet(fleetKey)
        var best: StoryAnchorSnapshotEntity? = null
        for (mate in mates) {
            val latest = dao.latestSnapshotFor(mate.characterUuid) ?: continue
            val src = AnchorSource.fromRaw(latest.sourceRaw)
            if (src != AnchorSource.WORLD_SYNC && src != AnchorSource.MANUAL && src != AnchorSource.DIRECTOR) continue
            if (best == null || latest.capturedAt > best!!.capturedAt) best = latest
        }
        return best
    }

    /** [zCODE] P2 预留：同团成员 uuid（大事件旁路选卡/接地选角用；无团=空）。 */
    suspend fun fleetMatesFor(characterUuid: String): List<String> = withContext(Dispatchers.IO) {
        val fleet = dao.fleetKeyOfCharacter(characterUuid) ?: return@withContext emptyList()
        dao.membersOfFleet(fleet).map { it.characterUuid }.filter { it != characterUuid }
    }

    /** [zCODE] P4 预留：director 广播语义 = applyDirectorEvent 落行后调 [broadcastToFleetMates]（源=director 在白名单）。 */

    /** [zCODE] 追加项A：角色删除级联清理（CharacterDeletionCleaner 调）。 */
    suspend fun deleteAllForCharacter(characterUuid: String) = withContext(Dispatchers.IO) {
        dao.deleteAnchorsForCharacter(characterUuid)
        dao.deleteLedgerForCharacter(characterUuid)
        dao.deleteFleetMembershipForCharacter(characterUuid)
    }

    /** 在场名单 JSON 编码（kotlinx；空列表 = 空串——历史行空串不回填同口径）。 */
    private fun encodePresentList(entries: List<AnchorBlockParser.PresentEntry>): String {
        if (entries.isEmpty()) return ""
        return runCatching {
            val json = kotlinx.serialization.json.Json { encodeDefaults = false }
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PresentEntryDto.serializer()),
                entries.map { PresentEntryDto(it.name, it.present, it.location) },
            )
        }.getOrDefault("")
    }

    @kotlinx.serialization.Serializable
    private data class PresentEntryDto(val name: String, val present: Boolean, val location: String = "")


    /** 当前锚点（最新行；manual 优先：同 capturedAt 冲突时 manual 行后写覆盖，读取取最新即自然生效）。 */
    suspend fun currentAnchorFor(characterUuid: String): StoryAnchorSnapshotEntity? =
        withContext(Dispatchers.IO) { dao.latestSnapshotFor(characterUuid) }

    fun observeCurrentAnchor(characterUuid: String) = dao.observeLatestSnapshotFor(characterUuid)

    /**
     * 时效过滤后的锚点：超过 [maxAgeMs] 视为不可用返回 null（调用侧自选档位：硬约束用
     * [AnchorVocabulary.FRESH_MAX_AGE_MS]，软参考用 [AnchorVocabulary.AGING_MAX_AGE_MS]）。
     */
    suspend fun freshAnchorFor(
        characterUuid: String,
        maxAgeMs: Long = AnchorVocabulary.FRESH_MAX_AGE_MS,
        nowMillis: Long = System.currentTimeMillis(),
    ): StoryAnchorSnapshotEntity? {
        val latest = currentAnchorFor(characterUuid) ?: return null
        return if (nowMillis - latest.effectiveAt <= maxAgeMs) latest else null
    }

    /** 近期已完成事件清单（读取处1「不得重新执行」注入数据源）。 */
    suspend fun recentCompletedEvents(
        characterUuid: String,
        sinceMillis: Long = System.currentTimeMillis() - AnchorVocabulary.AGING_MAX_AGE_MS,
    ): List<StoryEventLedgerEntity> = withContext(Dispatchers.IO) { dao.recentLedgerFor(characterUuid, sinceMillis) }

    /**
     * 补齐提示文案（裁决2：仅 stale 时提）。非 stale → null（不注入）。
     * 接线在评审点2（读取处1 注入段）；本 API 先备好，含位置 + 距今时长，供模型判断锚点是否仍成立。
     */
    suspend fun anchorNudgeText(
        characterUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        val latest = currentAnchorFor(characterUuid) ?: return null
        if (AnchorVocabulary.freshnessOf(latest.effectiveAt, nowMillis) != AnchorVocabulary.FreshnessLevel.STALE) return null
        val hours = max(1L, (nowMillis - latest.effectiveAt) / 3_600_000L)
        val loc = latest.locationRaw.ifBlank { "（位置未记录）" }
        return "【锚点待补齐】上一锚点已是 $hours 小时前：$loc。你的回复请顺带用一行 [场景：当前地点·时间] 标注此刻位置。"
    }

    // ── 内部 ──

    /** 幂等写入（附加条件1）：预检 + IGNORE 双保险；跳过时记一笔 debug 日志（幂等命中是正常路径，不刷屏 warn）。 */
    private suspend fun insertDeduped(snapshot: StoryAnchorSnapshotEntity): StoryAnchorSnapshotEntity? {
        if (dao.snapshotExists(snapshot.relatedMessageUUID, snapshot.sourceRaw)) {
            Log.d(TAG, "锚点快照幂等命中，跳过 char=${snapshot.characterUuid.take(8)} src=${snapshot.sourceRaw}")
            return null
        }
        val rowId = dao.insertSnapshot(snapshot)
        if (rowId == -1L) {
            Log.d(TAG, "锚点快照唯一索引兜底命中（并发写），跳过 char=${snapshot.characterUuid.take(8)}")
            return null
        }
        return snapshot
    }

    private companion object {
        const val TAG = "StoryStateRepo"

        /** [zCODE] 读取处5（评审附加条件3）：仅这两级登记插日程星标——dialog_ack（聊天随口确认）不插。 */
        val SCHEDULE_STAR_SOURCES = setOf(StoryEventSource.OFFLINE_MEETING, StoryEventSource.DIRECTOR)

        /** [zCODE] 读取处5：星标事件类型值（复用见面星标视觉家族；String 列新值零 schema 变更，同 P0 superseded 先例）。 */
        const val EVENT_TYPE_STORY_LEDGER = "storyLedger"
    }
}
