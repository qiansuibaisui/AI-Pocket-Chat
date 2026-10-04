package com.situ.aichat.notification

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ZD-13 行为金样：日程支由头必须以**角色自指**前缀渲染。
 * 旧锁定前缀「TA 的日程：」在到点侧框架「你是{角色}，给{user}发一条消息」里，「TA」被模型
 * 错绑到收件人——真机实锤（2026-10-04）：艾斯把自己日程的宴席吃喝归因给 user（「刚才看着你
 * 说得那么香，怎么不叫我一起」）。断言生成行为而非补丁存在：由头成品里可错绑字样绝迹。
 */
class ProactiveOccasionTextTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val start: Long = Instant.parse("2026-10-04T11:00:00Z").toEpochMilli()
    private val end: Long = Instant.parse("2026-10-04T14:00:00Z").toEpochMilli()

    private fun event(
        activity: String = "全船宴席",
        location: String = "甲板",
        moodEmoji: String = "🔥",
        moodText: String? = "尽兴",
        innerThought: String? = "热闹就好",
    ) = ScheduleEventEntity(
        uuid = "e1", scheduleUuid = "s1", startTime = start, endTime = end,
        activity = activity, location = location, moodEmoji = moodEmoji,
        moodText = moodText, innerThought = innerThought,
    )

    @Test fun occasionForEvent_rendersSelfReferentialPrefix_neverTaDeixis() {
        val text = ProactiveOccasionText.occasionForEvent(event(), zone)
        assertTrue("前缀=角色自指（框架内「你」恒=角色）", text.startsWith("你自己的日程：[11:00-14:00] 全船宴席"))
        assertFalse("可错绑收件人的「TA 的日程」绝迹", text.contains("TA 的日程"))
        assertTrue(text.contains("在甲板"))
        assertTrue(text.contains("心情🔥尽兴"))
        assertTrue(text.contains("内心想：热闹就好"))
    }

    @Test fun occasionForEvent_emptyDetails_leavesTimeAndActivityOnly() {
        val bare = ProactiveOccasionText.occasionForEvent(
            event(location = "", moodEmoji = "", moodText = null, innerThought = null), zone,
        )
        assertEquals("你自己的日程：[11:00-14:00] 全船宴席", bare)
    }

    /** 回退支由头（图纸 §3.1 锁定文案）不含日程指称，ZD-13 不波及。 */
    @Test fun occasionForCategory_lockedFallbacksUntouched() {
        assertEquals("早安问候", ProactiveOccasionText.occasionForCategory("morning"))
        assertEquals("晚间问候", ProactiveOccasionText.occasionForCategory("evening"))
        assertEquals("突然想到什么，想分享", ProactiveOccasionText.occasionForCategory("random"))
    }
}
