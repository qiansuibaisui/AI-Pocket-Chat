package com.situ.aichat.notification

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.prompt.notification.ProactiveMessageComposer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 主动通知的 occasion（由头）文案（图纸 §3.1 **锁定格式 / 锁定文案**，改动须过审）。
 *
 * 2026-08-28 自 [NotificationScheduler] companion **只搬不改**迁出（拆分账本预授权拆缝·两函数体与
 * KDoc 逐字节同源，仅去 companion 包裹）——由头随排程烤进闹钟 payload，到点侧
 * `ProactiveMessageComposer` 拿它写正文，**格式改一侧必须同步另一侧**。
 */
object ProactiveOccasionText {

    /**
     * 日程支由头：`你自己的日程：[HH:mm-HH:mm] 活动（在地点，心情🙂描述，内心想：独白）`。
     * 非空字段才出对应逗号段；三段全空则只留时段与活动。纯函数（internal 供单测）。
     *
     * ZD-13（2026-10-04 真机实锤）：原图纸 §3.1 锁定前缀「TA 的日程：」在到点侧框架
     * 「你是{角色}，给{user}发一条消息」里，代词「TA」被模型错绑到**收件人**——角色把自己的
     * 日程行为归因给 user（艾斯宴席反咬「刚才看着你说得那么香，怎么不叫我一起」）。
     * 前缀改角色自指「你自己的日程：」（框架内「你」恒=角色，绑不上别人）；图纸 §3.1 同步改。
     * 旧排程已烤进闹钟的「TA 的日程：」由头由 [ProactiveMessageComposer] 侧归一化兜住。
     */
    internal fun occasionForEvent(event: ScheduleEventEntity, zone: ZoneId): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val start = formatter.format(Instant.ofEpochMilli(event.startTime))
        val end = formatter.format(Instant.ofEpochMilli(event.endTime))
        val details = buildList {
            if (event.location.isNotEmpty()) add("在${event.location}")
            if (event.moodEmoji.isNotEmpty()) add("心情${event.moodEmoji}${event.moodText ?: ""}")
            if (!event.innerThought.isNullOrEmpty()) add("内心想：${event.innerThought}")
        }
        val suffix = if (details.isEmpty()) "" else "（${details.joinToString("，")}）"
        return "你自己的日程：[$start-$end] ${event.activity}$suffix"
    }

    /** 回退支由头（图纸 §3.1 锁定文案）。纯函数（internal 供单测）。 */
    internal fun occasionForCategory(category: String): String = when (category) {
        "morning" -> "早安问候"
        "evening" -> "晚间问候"
        "streak_remind" -> "想起对方，找个话题聊聊"
        "random" -> "突然想到什么，想分享"
        else -> ProactiveMessageComposer.FALLBACK_OCCASION
    }
}
