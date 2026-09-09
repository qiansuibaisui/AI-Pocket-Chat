package com.situ.aichat.prompt.schedule

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.EconomicStatusTier
import com.situ.aichat.economy.CharacterEconomicStateService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * T2-3（图纸 2026-07-10 日程专项 §7·E4/E5/E6/E14/E17/E18/E22）：素材收集器的选取/去重/边界/兜底。
 * 断言从图纸 §3.3 锁定算法独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleLivenessContextCollectorTest {

    private lateinit var db: AppDatabase
    private lateinit var collector: ScheduleLivenessContextCollector

    private val zone = ZoneOffset.UTC
    private val dayStart = LocalDate.of(2026, 7, 10).atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEnd = LocalDate.of(2026, 7, 11).atStartOfDay(zone).toInstant().toEpochMilli()
    private val uuid = "c1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        collector = ScheduleLivenessContextCollector(
            db.promiseDao(), db.openLoopDao(), db.meetingAppointmentDao(), db.offlineMeetingMemoryDao(),
            db.scheduleDao(), db.currencyDao(), CharacterEconomicStateService(db.currencyDao()),
        )
        runBlocking { db.characterDao().upsert(CharacterEntity(uuid = uuid, name = "夏晴子", creationDate = 0L)) }
    }

    @After
    fun tearDown() = db.close()

    private fun promise(content: String, dueAt: Long?, loopUuid: String? = null) = PromiseEntity(
        uuid = UUID.randomUUID().toString(), characterUuid = uuid, content = content,
        dueAtMillis = dueAt, openLoopUuid = loopUuid, createdAtMillis = 1L, updatedAtMillis = 1L,
    )

    private fun loop(id: String, content: String, dueAt: Long? = null, createdAt: Long = 1L) = OpenLoopEntity(
        uuid = id, conversationUuid = "conv", characterUuid = uuid, content = content,
        typeRaw = "user_event", dueAt = dueAt, createdAt = createdAt,
    )

    private fun meeting(status: String, at: Long, granularity: String = "exact", rawWhen: String = "") =
        MeetingAppointmentEntity(
            characterUuid = uuid, conversationUuid = "conv", status = status, scheduledAt = at,
            timeGranularity = granularity, rawWhenText = rawWhen, location = "美术馆", activity = "看展",
        )

    // ── E4 约定当日时区边界 ──

    @Test
    fun `E4 dueAt恰为今天0点属今天_恰为明天0点属未来`() = runBlocking {
        db.promiseDao().upsert(promise("今天的约定", dayStart))
        db.promiseDao().upsert(promise("明天的约定", dayEnd))
        val ctx = collector.collectFor(uuid, dayStart, zone)
        assertEquals(listOf("今天的约定"), ctx.todayPromises)
        assertEquals(listOf("明天的约定"), ctx.upcomingPromises.map { it.content })
        assertEquals("7月11日", ctx.upcomingPromises.single().dueDateText)
    }

    // ── E5 见面约定三态 ──

    @Test
    fun `E5 仅confirmed且当日的见面进块_proposed与过点与明天不进`() = runBlocking {
        val noon = dayStart + 12 * 3_600_000L
        db.meetingAppointmentDao().insert(meeting("proposed", noon))
        db.meetingAppointmentDao().insert(meeting("confirmed", dayStart - 3_600_000L)) // 昨天(activeForCharacter 仍返回·按日过滤)
        db.meetingAppointmentDao().insert(meeting("confirmed", dayEnd + 3_600_000L))   // 明天
        db.meetingAppointmentDao().insert(meeting("confirmed", noon))                  // 今天 ✓
        val ctx = collector.collectFor(uuid, dayStart, zone)
        assertEquals(1, ctx.todayMeetings.size)
        assertEquals("12:00", ctx.todayMeetings.single().timeText)
        assertEquals("美术馆", ctx.todayMeetings.single().location)
    }

    @Test
    fun `E22 dayOnly空rawWhen_timeText空串防今天今天_非空用原话`() = runBlocking {
        // 图纸 D-1 修订：渲染模板自带「今天」前缀，兜底若给「今天」会拼成「今天今天」（梦剧场 D7 同类坑）→ 兜底空串。
        db.meetingAppointmentDao().insert(meeting("confirmed", dayStart + 1000L, granularity = "dayOnly", rawWhen = ""))
        val ctx1 = collector.collectFor(uuid, dayStart, zone)
        assertEquals("", ctx1.todayMeetings.single().timeText)
        db.meetingAppointmentDao().insert(meeting("confirmed", dayStart + 2000L, granularity = "vague", rawWhen = "傍晚吧"))
        val ctx2 = collector.collectFor(uuid, dayStart, zone)
        assertTrue(ctx2.todayMeetings.map { it.timeText }.contains("傍晚吧"))
    }

    // ── E6 约定↔惦记桥接去重 ──

    @Test
    fun `E6 被约定桥接的惦记行剔除_其余保留`() = runBlocking {
        db.openLoopDao().upsert(loop("L1", "答应陪用户去看展"))
        db.openLoopDao().upsert(loop("L2", "用户下周面试"))
        db.promiseDao().upsert(promise("陪用户去看展", dayEnd + 1000L, loopUuid = "L1"))
        val ctx = collector.collectFor(uuid, dayStart, zone)
        assertEquals(listOf("用户下周面试"), ctx.openLoops)
    }

    @Test
    fun `惦记排序_临期优先再新建优先_上限3`() = runBlocking {
        db.openLoopDao().upsert(loop("L1", "无期最旧", dueAt = null, createdAt = 1L))
        db.openLoopDao().upsert(loop("L2", "无期最新", dueAt = null, createdAt = 9L))
        db.openLoopDao().upsert(loop("L3", "临期远", dueAt = dayEnd + 999_999L, createdAt = 2L))
        db.openLoopDao().upsert(loop("L4", "临期近", dueAt = dayStart + 1000L, createdAt = 3L))
        val ctx = collector.collectFor(uuid, dayStart, zone)
        assertEquals(listOf("临期近", "临期远", "无期最新"), ctx.openLoops)
    }

    @Test
    fun `未来约定按dueAt升序上限3`() = runBlocking {
        for (i in 4 downTo 1) db.promiseDao().upsert(promise("约定$i", dayEnd + i * 86_400_000L))
        val ctx = collector.collectFor(uuid, dayStart, zone)
        assertEquals(listOf("约定1", "约定2", "约定3"), ctx.upcomingPromises.map { it.content })
    }

    // ── 余温 ──

    @Test
    fun `余温_昨天见面进块_三天前不进_取最近一次`() = runBlocking {
        // [zCODE] P1·第2项 日期归属修后：dayWord 以**真实 now** 为界（今天/昨天/前天）——夹具须锚定真实今天，
        // 固定历史日期会让全部样本落"前天"（类级 dayStart=2026-07-10 是为 E17 摘要准备的，此处不复用）。
        val realTodayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        fun memory(startedAt: Long, activity: String) = OfflineMeetingMemoryEntity(
            uuid = UUID.randomUUID().toString(), characterUuid = uuid, startedAtMillis = startedAt,
            location = "公园", activity = activity, createdAtMillis = 1L, updatedAtMillis = 1L,
        )
        db.offlineMeetingMemoryDao().upsert(memory(realTodayStart - 3 * 86_400_000L, "三天前"))
        db.offlineMeetingMemoryDao().upsert(memory(realTodayStart - 30 * 3_600_000L, "前天夜里"))
        db.offlineMeetingMemoryDao().upsert(memory(realTodayStart - 10 * 3_600_000L, "昨天"))
        val ctx = collector.collectFor(uuid, realTodayStart, zone)
        val afterglow = ctx.recentMeetingAfterglow!!
        assertEquals("昨天", afterglow.dayWord)
        assertEquals("昨天", afterglow.activity)
    }

    // ── E17 多日摘要 ──

    @Test
    fun `E17 无历史日程_摘要缺席`() = runBlocking {
        assertTrue(collector.collectFor(uuid, dayStart, zone).recentDaysDigest.isEmpty())
    }

    @Test
    fun `摘要_滤睡眠_每日上限3_近在前_M月d日格式`() = runBlocking {
        suspend fun day(offsetDays: Int, activities: List<String>) {
            val dMillis = LocalDate.of(2026, 7, 10).minusDays(offsetDays.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val sUuid = "s$offsetDays"
            db.scheduleDao().insertScheduleWithEvents(
                CharacterDailyScheduleEntity(uuid = sUuid, characterUuid = uuid, date = dMillis, generatedAt = 1L),
                activities.mapIndexed { i, a ->
                    ScheduleEventEntity(
                        uuid = UUID.randomUUID().toString(), scheduleUuid = sUuid, startTime = dMillis + i,
                        endTime = dMillis + i + 1, periodLabel = "上午", location = "家里", activity = a,
                        moodEmoji = "🙂", sortOrder = i,
                    )
                },
            )
        }
        day(2, listOf("睡觉", "画画", "散步", "看剧", "读书")) // 滤睡 → 画画/散步/看剧（take3）
        day(4, listOf("上班"))
        val digest = collector.collectFor(uuid, dayStart, zone).recentDaysDigest
        assertEquals(listOf("7月8日：上午画画、上午散步、上午看剧", "7月6日：上午上班"), digest)
    }

    // ── E14 经济档 ──

    @Test
    fun `E14 无钱包null_有薪按余额判档`() = runBlocking {
        assertNull(collector.economicTierFor(uuid))
        db.currencyDao().insertCharacterWallet(
            CharacterWalletEntity(characterUuid = uuid, coinBalance = 10_000, monthlySalary = 1_000),
        )
        assertEquals(EconomicStatusTier.COMFORTABLE, collector.economicTierFor(uuid))
    }

    @Test
    fun `E14 月薪0_null块缺席`() = runBlocking {
        db.currencyDao().insertCharacterWallet(CharacterWalletEntity(characterUuid = uuid, coinBalance = 500, monthlySalary = 0))
        assertNull(collector.economicTierFor(uuid))
    }

    // ── E18 单源失败兜底 ──

    @Test
    fun `E18 约定DAO抛异常_该块缺席其余照常_不拦生成`() = runBlocking {
        db.openLoopDao().upsert(loop("L1", "惦记仍在"))
        val brokenPromiseDao = mockk<com.situ.aichat.data.local.dao.PromiseDao>()
        coEvery { brokenPromiseDao.openByCharacter(any()) } throws IllegalStateException("db broke")
        val broken = ScheduleLivenessContextCollector(
            brokenPromiseDao, db.openLoopDao(), db.meetingAppointmentDao(), db.offlineMeetingMemoryDao(),
            db.scheduleDao(), db.currencyDao(), CharacterEconomicStateService(db.currencyDao()),
        )
        val ctx = broken.collectFor(uuid, dayStart, zone)
        assertTrue(ctx.todayPromises.isEmpty())
        assertTrue(ctx.upcomingPromises.isEmpty())
        assertEquals(listOf("惦记仍在"), ctx.openLoops)
    }
}
