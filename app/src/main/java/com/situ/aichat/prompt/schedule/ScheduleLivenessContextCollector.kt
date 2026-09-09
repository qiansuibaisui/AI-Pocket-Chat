package com.situ.aichat.prompt.schedule

import android.util.Log
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.dao.OpenLoopDao
import com.situ.aichat.data.local.dao.PromiseDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.model.EconomicStatusTier
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.economy.CharacterEconomicStateService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日程生成的活人感素材收集（图纸 2026-07-10 日程专项 C5）。**全程只读**：约定/见面约定/惦记/见面档案/
 * 日程/钱包一律只查不写（兑现判定属对账系统职权，钱路写路径零碰）。每一源独立 runCatching——任何一路
 * 失败只缺席对应块、绝不拦日程生成（照 [ScheduleCoordinator.worldContextFor] 先例）；日志只打源名，
 * 绝不打内容与金额。时区运算一律 `atZone(zone).plusDays()`（DST 安全，照 backfillDateMillis 先例）。
 */
@Singleton
class ScheduleLivenessContextCollector @Inject constructor(
    private val promiseDao: PromiseDao,
    private val openLoopDao: OpenLoopDao,
    private val meetingAppointmentDao: MeetingAppointmentDao,
    private val offlineMeetingMemoryDao: OfflineMeetingMemoryDao,
    private val scheduleDao: ScheduleDao,
    private val currencyDao: CurrencyDao,
    private val economicStateService: CharacterEconomicStateService,
) {

    /** 满配素材包（仅今日正式生成调用；backfill 不调 = 恒精简）。[dateMillis] = 目标日当天 0 点。 */
    suspend fun collectFor(characterUuid: String, dateMillis: Long, zone: ZoneId): ScheduleLivenessContext {
        val dayStart = dateMillis
        val dayEnd = Instant.ofEpochMilli(dateMillis).atZone(zone).plusDays(1).toInstant().toEpochMilli()

        val openPromises = runCatching { promiseDao.openByCharacter(characterUuid) }
            .onFailure { Log.w(TAG, "素材收集失败[约定] char=$characterUuid: ${it.message}") }
            .getOrDefault(emptyList())

        val todayMeetings = runCatching { todayMeetings(characterUuid, dayStart, dayEnd, zone) }
            .onFailure { Log.w(TAG, "素材收集失败[见面约定] char=$characterUuid: ${it.message}") }
            .getOrDefault(emptyList())

        val todayPromises = openPromises
            .filter { it.dueAtMillis != null && it.dueAtMillis >= dayStart && it.dueAtMillis < dayEnd }
            .map { it.content }

        val upcoming = openPromises
            .filter { it.dueAtMillis != null && it.dueAtMillis >= dayEnd }
            .sortedBy { it.dueAtMillis }
            .take(UPCOMING_PROMISE_LIMIT)
            .map {
                ScheduleLivenessContext.UpcomingPromise(
                    content = it.content,
                    dueDateText = DATE_FORMAT.format(Instant.ofEpochMilli(it.dueAtMillis!!).atZone(zone)),
                )
            }

        // 惦记：剔除被进行中约定桥接的行（openLoopUuid·图纸 §0.②-6），防同一件事双列。
        val bridgedLoopUuids = openPromises.mapNotNull { it.openLoopUuid }.toSet()
        val openLoops = runCatching {
            openLoopDao.openByCharacter(characterUuid)
                .filterNot { it.uuid in bridgedLoopUuids }
                .sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { -it.createdAt }))
                .take(OPEN_LOOP_LIMIT)
                .map { it.content }
        }.onFailure { Log.w(TAG, "素材收集失败[惦记] char=$characterUuid: ${it.message}") }.getOrDefault(emptyList())

        val afterglow = runCatching { recentAfterglow(characterUuid, dayStart, zone) }
            .onFailure { Log.w(TAG, "素材收集失败[见面余温] char=$characterUuid: ${it.message}") }
            .getOrNull()

        val digest = runCatching { recentDaysDigest(characterUuid, dateMillis, zone) }
            .onFailure { Log.w(TAG, "素材收集失败[多日摘要] char=$characterUuid: ${it.message}") }
            .getOrDefault(emptyList())

        return ScheduleLivenessContext(
            todayMeetings = todayMeetings,
            todayPromises = todayPromises,
            upcomingPromises = upcoming,
            openLoops = openLoops,
            recentMeetingAfterglow = afterglow,
            recentDaysDigest = digest,
        )
    }

    /** 经济档（今日与 backfill 都注入·拍板⑤）：无钱包/月薪≤0 → null = 块缺席。只读、只出档位标签。 */
    suspend fun economicTierFor(characterUuid: String): EconomicStatusTier? = runCatching {
        val wallet = currencyDao.getCharacterWallet(characterUuid) ?: return@runCatching null
        economicStateService.tier(characterUuid, wallet.monthlySalary, wallet.coinBalance)
    }.onFailure { Log.w(TAG, "素材收集失败[经济档] char=$characterUuid: ${it.message}") }.getOrNull()

    // ── 内部 ──────────────────────────────────────────────────────────

    /** 今天已确认（CONFIRMED）的见面约定；EXACT 给 HH:mm，DAY_ONLY/VAGUE 用模型原话（空则「今天」）。 */
    private suspend fun todayMeetings(
        characterUuid: String,
        dayStart: Long,
        dayEnd: Long,
        zone: ZoneId,
    ): List<ScheduleLivenessContext.MeetingLine> =
        meetingAppointmentDao.activeForCharacter(characterUuid)
            .filter { MeetingStatus.fromRaw(it.status) == MeetingStatus.CONFIRMED }
            .filter { it.scheduledAt in dayStart until dayEnd }
            .map { appt ->
                // 模糊时刻兜底空串而非「今天」：渲染模板已带「今天」前缀，双词会拼成「今天今天」
                // （梦剧场 D7「约约」同类坑·图纸 D-1 修订）。
                val timeText = when (MeetingTimeGranularity.fromRaw(appt.timeGranularity)) {
                    MeetingTimeGranularity.EXACT ->
                        TIME_FORMAT.format(Instant.ofEpochMilli(appt.scheduledAt).atZone(zone))
                    MeetingTimeGranularity.DAY_ONLY, MeetingTimeGranularity.VAGUE ->
                        appt.rawWhenText.trim()
                }
                ScheduleLivenessContext.MeetingLine(timeText, appt.location, appt.activity)
            }

    /** 48h 内（[dayStart-2天, dayStart)）最近一次结构化见面 → 余温行；无则 null。 */
    private suspend fun recentAfterglow(
        characterUuid: String,
        dayStart: Long,
        zone: ZoneId,
    ): ScheduleLivenessContext.AfterglowLine? {
        val windowStart = Instant.ofEpochMilli(dayStart).atZone(zone).minusDays(2).toInstant().toEpochMilli()
        val yesterdayStart = Instant.ofEpochMilli(dayStart).atZone(zone).minusDays(1).toInstant().toEpochMilli()
        val latest = offlineMeetingMemoryDao.byCharacter(characterUuid)
            .filter { it.kindRaw == MEETING_KIND && it.startedAtMillis in windowStart until dayStart }
            .maxByOrNull { it.startedAtMillis } ?: return null
        val dayWord = afterglowDayWord(latest.startedAtMillis, dayStart, zone)
        return ScheduleLivenessContext.AfterglowLine(dayWord, latest.location, latest.activity)
    }

    /** D-2..D-5 每日一行（近在前）：非睡眠活动 take 3；空日跳过。 */
    private suspend fun recentDaysDigest(
        characterUuid: String,
        dateMillis: Long,
        zone: ZoneId,
    ): List<String> {
        val lines = mutableListOf<String>()
        for (k in DIGEST_DAY_OFFSETS) {
            val dayZoned = Instant.ofEpochMilli(dateMillis).atZone(zone).minusDays(k.toLong())
            val dayMillis = dayZoned.toInstant().toEpochMilli()
            val schedule = scheduleDao.scheduleFor(characterUuid, dayMillis) ?: continue
            val items = scheduleDao.eventsForSchedule(schedule.uuid)
                .sortedWith(compareBy({ it.sortOrder }, { it.startTime }))
                .filterNot { it.activity.contains("睡") }
                .take(DIGEST_EVENT_LIMIT)
            if (items.isEmpty()) continue
            val joined = items.joinToString("、") { "${it.periodLabel}${it.activity}" }
            lines.add("${DATE_FORMAT.format(dayZoned)}：$joined")
        }
        return lines
    }

    private companion object {
        const val TAG = "ScheduleLiveness"
        const val MEETING_KIND = "meeting"
        const val OPEN_LOOP_LIMIT = 3
        const val UPCOMING_PROMISE_LIMIT = 3
        const val DIGEST_EVENT_LIMIT = 3
        val DIGEST_DAY_OFFSETS = 2..5
        /** 面向 LLM 的日期/时刻格式器统一 Locale.ROOT（PITFALLS §1c 先例）。 */
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.ROOT)
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}

/**
 * [zCODE] P1·第2项 读取处2 顺带修（日期归属）：余温 dayWord 三分——生成**未来日**日程时，窗口含"今天"发生的
 * 见面（dayStart=明天 0 点 > 今天），旧二分法把它判成「昨天」（当天聊天被标昨天·实测在案）。修正：以**真实今天**
 * （now 的当日 0 点）为界，今天发生的见面标「今天」。顶层纯函数便于单测。
 */
internal fun afterglowDayWord(startedAtMillis: Long, dayStart: Long, zone: ZoneId, nowMillis: Long = System.currentTimeMillis()): String {
    // 分界全部以 **now 视角** 为准（用户读到的"今天/昨天"）：dayStart（被生成的目标日）只决定上游窗口取舍，
    // 不参与措辞分界——否则生成明日日程时，昨日见面会被目标日视角误判成"前天"。
    val todayStart = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val yesterdayStart = todayStart - 86_400_000L // 近似一日（措辞粒度足够；严格跨 DST 由上 LocalDate 路径兜底）
    return when {
        startedAtMillis >= todayStart -> "今天"
        startedAtMillis >= yesterdayStart -> "昨天"
        else -> "前天"
    }
}
