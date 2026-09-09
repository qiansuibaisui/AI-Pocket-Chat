package com.situ.aichat.prompt.schedule

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.messageLlmSafeText
import com.situ.aichat.world.stage.WorldScheduleContext
import com.situ.aichat.world.stage.WorldStageService
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.ScheduleGenerationWorker
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日程生成编排（P5.1）。1:1 对齐 iOS `ScheduleCoordinator+Generation` 的 `ensureTodaySchedules`：
 * 为「今天还没有正式日程」的每个角色按需调 [ScheduleGenerationService] 生成。
 *
 * 由 WorkManager 每日 worker + app 回前台触发（见 [com.situ.aichat.work.ScheduleGenerationWorker]）。
 * 天气检查 / 补生成历史 / 聊天写回按 iOS 分层延后。时区当前用设备时区（反查时区延后）。
 */
@Singleton
class ScheduleCoordinator @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val characterDao: CharacterDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val userProfileDao: UserProfileDao,
    private val settingsRepo: SettingsRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val generationService: ScheduleGenerationService,
    private val backgroundScheduler: BackgroundScheduler,
    private val stageService: WorldStageService,
    private val livenessCollector: ScheduleLivenessContextCollector,
    private val storyStateRepository: com.situ.aichat.data.repository.StoryStateRepository, // [zCODE] P1·第2项 读取处2
) {
    private val generationMutex = Mutex()

    /** [zCODE] P1·第2项：fresh 锚点（取用失败 → null = fail-open 不约束）。 */
    private suspend fun freshAnchorForSchedule(characterUuid: String) =
        runCatching { storyStateRepository.freshAnchorFor(characterUuid) }.getOrNull()

    private val _isGenerating = MutableStateFlow(false)

    /** 是否正在生成今日日程（对齐 iOS `ScheduleCoordinator.isGenerating`）。资料页日程卡据此显加载态。 */
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _failedCharacterUuids = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 今日日程生成失败的角色集（对齐 iOS `failedCharacterUUIDs`）。成功生成即移除、失败即加入；
     * 资料页日程卡据此显「生成失败 + 重试」。**这是最小可观察状态**——不移植 iOS 的 60s Timer
     * toolbar 缓存（那是 iOS 同步查库重渲染的平台补丁，安卓 Flow/Compose 天然规避）。
     */
    val failedCharacterUuids: StateFlow<Set<String>> = _failedCharacterUuids.asStateFlow()

    /** 失败集上次按「当天」重置的日期（当天 0 点毫秒）；跨日时清空失败集（对齐 iOS lastRetryResetDate）。 */
    private var lastFailedResetDayMillis: Long = -1L

    /**
     * 各角色今日日程生成的重试次数（对齐 iOS `scheduleRetryCount`，跨日清空）；达 [MAX_RETRIES] 即放弃当天自动重试。
     * 仅在持有 [generationMutex] 的路径里读写（ensureTodaySchedules / 跨日重置），无并发问题。进程被杀则丢失=与 iOS 内存态一致。
     */
    private val scheduleRetryCount = mutableMapOf<String, Int>()

    /** 确保所有角色今天都有日程；缺则生成。被多入口（worker + 前台）调用，自带串行化与系统开关守卫。 */
    suspend fun ensureTodaySchedules() {
        val settings = settingsRepo.getAppSettings()
        if (!settings.scheduleSystemEnabled) {
            Log.i(TAG, "日程系统已关闭，跳过")
            return
        }
        // 防并发重复生成（对齐 iOS isGenerating 守卫）；正在跑就直接返回。
        if (!generationMutex.tryLock()) {
            Log.d(TAG, "日程生成进行中，跳过本次触发")
            return
        }
        try {
            val characters = characterDao.getAll()
            if (characters.isEmpty()) {
                Log.i(TAG, "无角色，跳过日程生成")
                return
            }

            val zone = ZoneId.systemDefault()
            val todayMillis = startOfDayMillis(System.currentTimeMillis(), zone)
            // 新的一天：清空昨日失败集（对齐 iOS lastRetryResetDate；即便今日均已就绪也清，避免陈旧失败标记）。
            if (lastFailedResetDayMillis != todayMillis) {
                _failedCharacterUuids.value = emptySet()
                scheduleRetryCount.clear()
                backgroundScheduler.cancel(ScheduleGenerationWorker.UNIQUE_RETRY)
                lastFailedResetDayMillis = todayMillis
            }
            val pending = characters.filter { scheduleNeedsGeneration(it.uuid, todayMillis) }
            if (pending.isEmpty()) {
                Log.d(TAG, "今日日程均已就绪")
                return
            }

            val config = apiConfigRepo.resolveConfigValues(ApiFunction.SCHEDULE_GENERATION)
            if (config == null) {
                Log.i(TAG, "无可用 API 配置，跳过日程生成")
                return
            }

            val ordered = shuffledCharactersForDay(pending, todayMillis)
            val crossLevel = settings.crossCharacterLevel
            Log.d(TAG, "开始生成今日日程：${ordered.size} 个角色待生成")

            _isGenerating.value = true
            for (character in ordered) {
                generateOne(character, todayMillis, zone, crossLevel, config)
            }
            // 本轮跑完后，仍失败的角色排自动延迟重试（5/15/30 分逐级，每角色每天最多 3 次）。
            scheduleDelayedRetryIfNeeded(_failedCharacterUuids.value)
        } finally {
            _isGenerating.value = false
            generationMutex.unlock()
        }
    }

    /**
     * 今日生成跑完后，对仍失败的角色排自动延迟重试（对齐 iOS `scheduleDelayedRetry`）：
     * 按 [RETRY_DELAYS_SECONDS] 升级延迟（5/15/30 分），每角色每天最多 [MAX_RETRIES] 次，达上限即放弃当天自动重试。
     * 复用既有 [ScheduleGenerationWorker]——其 ensure 只重生「缺今日日程」的角色（= 仍失败集），故全量 ensure 等价于 iOS 的
     * 定向 retry；到点跨进程重试比 iOS 内存 Task 更扛国行杀后台。REPLACE 唯一任务名 = iOS「取消+替换单一 retry task」。
     * worker 再跑 ensure 时会再调本方法 → 自然链式重试、所有角色达上限后 [nextScheduleRetry] 返 null 自停。
     */
    private fun scheduleDelayedRetryIfNeeded(failed: Set<String>) {
        val decision = nextScheduleRetry(failed, scheduleRetryCount, RETRY_DELAYS_SECONDS, MAX_RETRIES) ?: return
        // 计数在「排入时」自增（iOS 在 sleep 后自增）：升级/封顶行为等价，且免去一次回调；无网时 requireNetwork 让任务等网不烧次数。
        scheduleRetryCount.clear()
        scheduleRetryCount.putAll(decision.updatedCounts)
        Log.d(TAG, "日程延迟重试：${decision.retryable.size} 个角色，延迟 ${decision.delaySeconds}s")
        backgroundScheduler.scheduleOneShot(
            uniqueName = ScheduleGenerationWorker.UNIQUE_RETRY,
            workerClass = ScheduleGenerationWorker::class.java,
            initialDelay = Duration.ofSeconds(decision.delaySeconds),
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.REPLACE,
        )
    }

    /**
     * 补算最近缺失的历史日程（14.7a，1:1 iOS `BackgroundTaskRunner.backfillMissedDays`）。
     * 每个角色从「最新一条日程的次日」补到「昨天」，最多保留最近 7 天（[backfillDateMillis]）。
     *
     * **必须排在 [ensureTodaySchedules] 之前**（worker doWork 已保证此序）：若先生成今天，MAX(date) 会跳到今天，
     * 反使补算锚点越过昨天 → 历史缺日永远补不上（对齐 iOS `runWithBackgroundRunner` 先 backfill 后 ensure 的顺序）。
     *
     * 补算用回顾性参数（对齐 iOS：weather=nil、recentSummary=null、crossLevel=0、isBackfill=true），事件 eventType=actual。
     * 无网/无配置/系统关/无既有日程（新角色）都安全跳过；generateSchedule 自带幂等（已生成的日跳过）。
     */
    suspend fun backfillMissedDays() {
        val settings = settingsRepo.getAppSettings()
        if (!settings.scheduleSystemEnabled) return
        // 与 ensureTodaySchedules 共用串行锁：正在生成就跳过本次补算（worker 会紧接着调 ensure，缺日下次再补）。
        if (!generationMutex.tryLock()) {
            Log.d(TAG, "日程生成进行中，跳过补算")
            return
        }
        try {
            val characters = characterDao.getAll()
            if (characters.isEmpty()) return
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.SCHEDULE_GENERATION) ?: run {
                Log.d(TAG, "无可用 API 配置，跳过历史日程补算")
                return
            }

            val zone = ZoneId.systemDefault()
            val todayMillis = startOfDayMillis(System.currentTimeMillis(), zone)
            val yesterdayMillis = Instant.ofEpochMilli(todayMillis).atZone(zone)
                .minusDays(1).toInstant().toEpochMilli()

            for (character in characters) {
                val latest = scheduleDao.latestScheduleDate(character.uuid) ?: continue
                val missing = backfillDateMillis(latest, yesterdayMillis, zone)
                if (missing.isEmpty()) continue
                Log.d(TAG, "补算 ${character.name} 历史日程 ${missing.size} 天")
                for (dateMillis in missing) {
                    backfillOne(character, dateMillis, zone, config)
                }
            }
        } finally {
            generationMutex.unlock()
        }
    }

    /**
     * 生成单角色某历史日的回顾性日程（调用方持有 [generationMutex]）。失败仅记日志、不进失败集
     * （失败集是「今日卡」UI 状态，历史日不可见，不应污染）。昨天事件取邻日做衔接（对齐 iOS 内部 yesterdaySchedule）。
     */
    private suspend fun backfillOne(
        character: CharacterEntity,
        dateMillis: Long,
        zone: ZoneId,
        config: ApiConfigValues,
    ) {
        val world = worldContextFor(character, dateMillis)
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }
        val request = ScheduleGenerationRequest(
            character = character,
            dateMillis = dateMillis,
            zone = zone,
            yesterdayEvents = yesterdayEvents(character.uuid, dateMillis, zone),
            recentConversationSummary = null,
            otherCharacterSchedules = emptyList(),
            crossCharacterLevel = 0,
            isBackfill = true,
            worldCityName = world?.cityName,
            worldPlaceNames = world?.placeNames ?: emptyList(),
            worldWeatherLine = world?.weatherLine,
            worldWeatherCondition = world?.weatherCondition,
            worldWeatherEmoji = world?.weatherEmoji,
            // 补算精简（拍板⑤）：只带经济档，liveness 恒 null——回顾日不编造约定/惦记/余温。
            economicTier = livenessCollector.economicTierFor(character.uuid),
            userName = userName,
        )
        try {
            generationService.generateSchedule(request, config)
        } catch (e: Exception) {
            Log.e(TAG, "历史日程补算失败: ${character.name} @ $dateMillis - ${e.message}")
        }
    }

    /**
     * 仅重生**该角色今日**日程（对齐 iOS `manualRetry(characterUUID:)`，资料页日程卡「生成失败→重试」）。
     * await 锁，确保排在任何 in-flight [ensureTodaySchedules] 之后执行；幂等（已生成则
     * [ScheduleGenerationService.generateSchedule] 跳过），成功后 Room Flow 自动推数据。
     */
    suspend fun retryTodayFor(characterUuid: String) {
        val settings = settingsRepo.getAppSettings()
        if (!settings.scheduleSystemEnabled) {
            Log.i(TAG, "日程系统已关闭，跳过单角色日程重试")
            return
        }
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.SCHEDULE_GENERATION) ?: run {
            Log.i(TAG, "无可用 API 配置，跳过单角色日程重试")
            return
        }
        // 乐观清除失败标记：UI 立即从「失败卡」转「加载卡」（对齐 iOS manualRetry 期间 isGenerating→Loading）；
        // 失败则 generateOne 重新加入。
        _failedCharacterUuids.update { it - characterUuid }
        generationMutex.withLock {
            _isGenerating.value = true
            try {
                val zone = ZoneId.systemDefault()
                val todayMillis = startOfDayMillis(System.currentTimeMillis(), zone)
                val character = characterDao.getByUuid(characterUuid) ?: return@withLock
                generateOne(character, todayMillis, zone, settings.crossCharacterLevel, config)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /** 生成单角色某日日程并更新失败集（成功移除、失败加入）。调用方负责持有 [generationMutex]。 */
    private suspend fun generateOne(
        character: CharacterEntity,
        todayMillis: Long,
        zone: ZoneId,
        crossLevel: Int,
        config: ApiConfigValues,
    ) {
        val world = worldContextFor(character, todayMillis)
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }
        val request = ScheduleGenerationRequest(
            character = character,
            dateMillis = todayMillis,
            zone = zone,
            yesterdayEvents = yesterdayEvents(character.uuid, todayMillis, zone),
            recentConversationSummary = recentConversationSummary(character, zone),
            otherCharacterSchedules = otherCharacterSchedules(character.uuid, todayMillis),
            crossCharacterLevel = crossLevel,
            worldCityName = world?.cityName,
            worldPlaceNames = world?.placeNames ?: emptyList(),
            worldWeatherLine = world?.weatherLine,
            worldWeatherCondition = world?.weatherCondition,
            worldWeatherEmoji = world?.weatherEmoji,
            // 活人感满配（图纸 C6·今日正式生成才有；collector 内部 per-源兜底，绝不拦生成）。
            economicTier = livenessCollector.economicTierFor(character.uuid),
            liveness = livenessCollector.collectFor(character.uuid, todayMillis, zone),
            userName = userName,
            // [zCODE] P1·第2项 读取处2：fresh 锚点才作硬约束输入（fail-open：超龄/无锚点不约束）。仅今日/未来
            // 正式生成带（backfill 历史日不带——锚点是"当前"状态，套到历史日是时空错置）。
            anchor = freshAnchorForSchedule(character.uuid),
            anchorFresh = freshAnchorForSchedule(character.uuid) != null,
        )
        try {
            generationService.generateSchedule(request, config)
            _failedCharacterUuids.update { it - character.uuid }
        } catch (e: Exception) {
            Log.e(TAG, "今日日程生成失败: ${character.name} - ${e.message}")
            _failedCharacterUuids.update { it + character.uuid }
        }
    }

    // MARK: - 判定与素材收集

    /**
     * 加入世界的角色注入世界上下文（W9d §3.5·只加输入·try/catch 失败退现行为 = 字节级不变）。
     * 未加入 → null（请求 worldXxx 皆默认 = 旧行为）；查询/解析任何异常 → null（绝不拦日程生成）。
     */
    private suspend fun worldContextFor(character: CharacterEntity, dateMillis: Long): WorldScheduleContext? {
        if (!character.joinedWorld) return null
        return runCatching { stageService.scheduleContextFor(character, dateMillis) }
            .onFailure { Log.w(TAG, "世界日程上下文获取失败(不拦生成): ${character.name} - ${it.message}") }
            .getOrNull()
    }

    private suspend fun scheduleNeedsGeneration(characterUuid: String, todayMillis: Long): Boolean {
        val schedule = scheduleDao.scheduleFor(characterUuid, todayMillis) ?: return true
        return schedule.generatedAt == null
    }

    private suspend fun yesterdayEvents(characterUuid: String, todayMillis: Long, zone: ZoneId): List<ScheduleEventEntity> {
        val yesterday = Instant.ofEpochMilli(todayMillis).atZone(zone).minusDays(1)
            .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val schedule = scheduleDao.scheduleFor(characterUuid, yesterday) ?: return emptyList()
        return scheduleDao.eventsForSchedule(schedule.uuid)
    }

    private suspend fun otherCharacterSchedules(
        excludingUuid: String,
        todayMillis: Long,
    ): List<ScheduleGenerationRequest.OtherSchedule> {
        return characterDao.getAll()
            .filter { it.uuid != excludingUuid }
            .mapNotNull { other ->
                val schedule = scheduleDao.scheduleFor(other.uuid, todayMillis) ?: return@mapNotNull null
                ScheduleGenerationRequest.OtherSchedule(
                    name = other.name,
                    events = scheduleDao.eventsForSchedule(schedule.uuid),
                )
            }
    }

    /**
     * 最近 2 天、跨角色全部会话的最近 6 条非空消息，按时间正序拼成「你/角色名：内容」文本。
     * 1:1 对齐 iOS `recentConversationSummary`（贴纸标记替换 M17 未做，先用原文）。
     */
    private suspend fun recentConversationSummary(character: CharacterEntity, zone: ZoneId): String? {
        val conversations = conversationDao.getByCharacter(character.uuid)
        if (conversations.isEmpty()) return null

        val since = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).minusDays(2)
            .toInstant().toEpochMilli()
        val recent = conversations.flatMap { messageDao.recentSinceForSummary(it.uuid, since, 6) }

        val summary = recent
            .sortedByDescending { it.timestamp }
            .mapNotNull { message ->
                // 结构化卡脱敏单源：礼物/红包卡→无金额·已拆红包事件→带金额(状态驱动)·通话/线下→丢弃，杜绝原始 JSON/amount 进日程 LLM。
                val safe = messageLlmSafeText(message) ?: return@mapNotNull null
                val role = if (message.roleRaw == ROLE_USER) "你" else character.name
                "$role：${safe.trim()}"
            }
            .take(6)
            .reversed()
            .joinToString("\n")
            .trim()
        return summary.ifEmpty { null }
    }

    // MARK: - 当天 0 点 + 按天确定性洗牌

    private fun startOfDayMillis(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * 按「当天」种子确定性洗牌待生成角色（对齐 iOS `shuffledCharactersForDay` 的意图：每天稳定但日间变化，
     * 影响多角色时跨角色引用的先后）。用 splitmix64 + Fisher-Yates；为不逐位复刻 Swift `random(in:using:)`
     * 的取模去偏，洗牌顺序不必与 iOS 逐位一致——仅观感无差的先后差异。
     */
    private fun shuffledCharactersForDay(characters: List<CharacterEntity>, dayStartMillis: Long): List<CharacterEntity> {
        if (characters.size <= 1) return characters
        val result = characters.toMutableList()
        val rng = SplitMix64(dayStartMillis / 1000L)
        for (i in result.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            if (j != i) {
                val tmp = result[i]
                result[i] = result[j]
                result[j] = tmp
            }
        }
        return result
    }

    private class SplitMix64(seed: Long) {
        private var state: ULong = if (seed == 0L) GOLDEN else seed.toULong()
        private fun next(): ULong {
            state += GOLDEN
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }
        fun nextInt(bound: Int): Int = (next() % bound.toULong()).toInt()
        private companion object { const val GOLDEN = 0x9E3779B97F4A7C15uL }
    }

    private companion object {
        const val TAG = "ScheduleCoord"
        const val ROLE_USER = "user"
        const val MAX_RETRIES = 3
        val RETRY_DELAYS_SECONDS = listOf(300L, 900L, 1800L)
    }
}

/** 一次日程延迟重试的决策（纯函数 [nextScheduleRetry] 产物）。 */
internal data class ScheduleRetryDecision(
    val delaySeconds: Long,
    val retryable: Set<String>,
    val updatedCounts: Map<String, Int>,
)

/**
 * 计算下一次日程延迟重试（纯函数，单测覆盖）。1:1 对齐 iOS `ScheduleCoordinator.scheduleDelayedRetry`：
 * 只重试次数 < [maxRetries] 的失败角色；全部达上限 → 返回 null（放弃当天）。延迟由「可重试集里的最大已重试次数」
 * 决定：`delays[min(maxCount, delays.lastIndex)]`（5/15/30 分逐级）。返回的 [ScheduleRetryDecision.updatedCounts]
 * 已在原计数表基础上对每个可重试角色 +1（达上限的角色保留原值不动）。
 */
internal fun nextScheduleRetry(
    failed: Set<String>,
    retryCount: Map<String, Int>,
    delays: List<Long> = listOf(300L, 900L, 1800L),
    maxRetries: Int = 3,
): ScheduleRetryDecision? {
    val retryable = failed.filter { (retryCount[it] ?: 0) < maxRetries }.toSet()
    if (retryable.isEmpty()) return null
    val maxCount = retryable.maxOf { retryCount[it] ?: 0 }
    val delay = delays[minOf(maxCount, delays.lastIndex)]
    val updated = retryCount.toMutableMap()
    for (uuid in retryable) updated[uuid] = (updated[uuid] ?: 0) + 1
    return ScheduleRetryDecision(delay, retryable, updated)
}

/**
 * 历史缺失日补算的日期清单（纯函数，单测覆盖）。给「最新日程的当天 0 点」[latestDayStartMillis] 与「昨天 0 点」
 * [yesterdayStartMillis]，返回需补算的各日 0 点毫秒（升序）：(latest 次日 … 昨天]，超 7 天只保留最近 7 天。
 *
 * 1:1 对齐 iOS `BackgroundTaskRunner.backfillDates(after:through:)`：latest>=yesterday（含 latest 在未来）→ 空；
 * 否则 latest+1..yesterday 闭区间；count>7 取 `suffix(7)`（丢最旧、留最近 7 天）。用 [zone] 做 DST 安全的按日推进。
 */
internal fun backfillDateMillis(
    latestDayStartMillis: Long,
    yesterdayStartMillis: Long,
    zone: ZoneId,
): List<Long> {
    val latest = Instant.ofEpochMilli(latestDayStartMillis).atZone(zone).toLocalDate()
    val yesterday = Instant.ofEpochMilli(yesterdayStartMillis).atZone(zone).toLocalDate()
    if (!latest.isBefore(yesterday)) return emptyList()
    val dates = mutableListOf<Long>()
    var current = latest.plusDays(1)
    while (!current.isAfter(yesterday)) {
        dates.add(current.atStartOfDay(zone).toInstant().toEpochMilli())
        current = current.plusDays(1)
    }
    return if (dates.size > 7) dates.takeLast(7) else dates
}
