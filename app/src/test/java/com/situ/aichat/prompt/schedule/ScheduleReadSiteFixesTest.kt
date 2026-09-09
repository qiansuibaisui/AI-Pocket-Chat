package com.situ.aichat.prompt.schedule

import com.situ.aichat.prompt.schedule.afterglowDayWord
import com.situ.aichat.ui.schedule.ScheduleTimelineLogic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** [zCODE] P1·第2项 读取处2 顺带两修：activityText 格式双写 + 余温 dayWord 日期归属。 */
class ScheduleReadSiteFixesTest {

    // ── 格式双写：「和「千岁」和千岁在集市」→ activity 已含人名不再前缀 ──

    @Test fun activity_text_no_nesting_when_name_already_in_activity() {
        assertEquals("与千岁在集市吃饭", ScheduleTimelineLogic.activityText("与千岁在集市吃饭", "「千岁」"))
        assertEquals("和千岁在集市", ScheduleTimelineLogic.activityText("和千岁在集市", "千岁"))
    }

    @Test fun activity_text_prefixes_when_name_absent() {
        // 生产行为：names 原样进「」（调用方传净名）；带括号输入照旧透传（与旧行为逐字节一致）
        assertEquals("和「千岁」一起吃了饭", ScheduleTimelineLogic.activityText("一起吃了饭", "千岁"))
        assertEquals("和「「千岁」」一起吃了饭", ScheduleTimelineLogic.activityText("一起吃了饭", "「千岁」"))
        assertEquals("一起吃了饭", ScheduleTimelineLogic.activityText("一起吃了饭", null))
        assertEquals("一起吃了饭", ScheduleTimelineLogic.activityText("一起吃了饭", "  "))
    }

    @Test fun activity_text_multi_name_partial_hit_exempt() {
        // 多名名单个命中即豁免（宁可少前缀不可双写）
        assertEquals("和小明逛了街", ScheduleTimelineLogic.activityText("和小明逛了街", "「千岁」，「小明」"))
    }

    // ── 日期归属：生成未来日日程时，今天发生的见面不再被标「昨天」──

    /** 用系统时区算当日 0 点（UTC 取模在非零时区会偏移，旧写法在 +8 区会差 8 小时）。 */
    private fun zoneTodayStart(now: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun afterglow_day_word_today_meeting_labeled_today() {
        val zone = ZoneId.systemDefault()
        val now = 1_780_000_000_000L
        val todayStart = zoneTodayStart(now, zone)
        val tomorrowStart = zoneTodayStart(now + 86_400_000L, zone)
        val todayMeeting = todayStart + 10 * 3600_000L
        // 生成明日的日程：窗口 [昨天0点, 明天0点) 含今天见面 → 应标「今天」（旧逻辑误标「昨天」）
        assertEquals("今天", afterglowDayWord(todayMeeting, tomorrowStart, zone, now))
    }

    @Test fun afterglow_day_word_yesterday_and_before_yesterday() {
        val zone = ZoneId.systemDefault()
        val now = 1_780_000_000_000L
        val todayStart = zoneTodayStart(now, zone)
        val tomorrowStart = zoneTodayStart(now + 86_400_000L, zone)
        val yesterdayMeeting = todayStart - 2 * 3600_000L
        assertEquals("昨天", afterglowDayWord(yesterdayMeeting, tomorrowStart, zone, now))
        // 前天的见面 → 「前天」
        val dayBeforeYesterday = todayStart - 86_400_000L - 3 * 3600_000L
        assertEquals("前天", afterglowDayWord(dayBeforeYesterday, tomorrowStart, zone, now))
    }
}
