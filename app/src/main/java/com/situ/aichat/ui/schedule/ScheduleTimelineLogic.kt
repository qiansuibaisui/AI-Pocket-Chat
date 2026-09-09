package com.situ.aichat.ui.schedule

import com.situ.aichat.data.local.entity.ScheduleEventEntity

/** 事件时态三态（1:1 iOS `TimeState`：past/current/future）。驱动时间线行的圆点/置灰/虚线视觉。 */
enum class TimeState { PAST, CURRENT, FUTURE }

/**
 * 日程 UI 的纯函数集（P14.2）。从 iOS `ScheduleTimelineCard` / `ScheduleFullDayView` / `ScheduleEventRow`
 * 的展示算法逐字反推；**全部纯函数、无 IO/无 Compose**，便于单测断言反推 iOS 真值。
 *
 * ⚠️ 排序键序陷阱：UI 展示序 = `compareBy(startTime, sortOrder)`（startTime 主键），与 `ScheduleDao`
 * 入库查询的 `sortOrder ASC, startTime ASC` **相反**。iOS 2026-04-24 加固改用 startTime 主键，避免个别
 * userInteraction 事件漏传 sortOrder=0 被挤到凌晨位置。**UI 必须用 [sortedEvents] 重排，不能依赖 DAO 顺序。**
 *
 * 时间均为绝对 epoch 毫秒（startTime/endTime 入库时已由 hour/minute 换算成绝对时刻），直接与 now 比较。
 * 日级比较（全天视图）用设备时区的「当天 0 点」毫秒，国行无夏令时，与日程 date 存储方式一致。
 */
internal object ScheduleTimelineLogic {

    /** 展示排序：startTime 升序为主键，sortOrder 升序为稳定性 tiebreak（1:1 iOS `sortedEvents`）。 */
    fun sortedEvents(events: List<ScheduleEventEntity>): List<ScheduleEventEntity> =
        events.sortedWith(compareBy({ it.startTime }, { it.sortOrder }))

    /**
     * 资料页今日卡的可见事件：仅「已开始」（startTime<=now，含进行中+已结束），按展示序排，最多 3 件取末尾
     * （最近的 3 件）。≤3 件（含空）原样返回。1:1 iOS `selectedEvents`（L147-153）。空 → 调用方整卡不渲染。
     */
    fun selectedEvents(events: List<ScheduleEventEntity>, now: Long): List<ScheduleEventEntity> {
        val visible = sortedEvents(events).filter { it.startTime <= now }
        return if (visible.size <= 3) visible else visible.takeLast(3)
    }

    /**
     * 单事件在「今天」内的时态（顺序敏感：先判已结束）。1:1 iOS `ScheduleTimelineCard.determineTimeState`
     * （L157-169）：endTime<=now → PAST；startTime<=now<endTime → CURRENT；否则 FUTURE。
     */
    fun timelineTimeState(event: ScheduleEventEntity, now: Long): TimeState = when {
        event.endTime <= now -> TimeState.PAST
        event.startTime <= now && event.endTime > now -> TimeState.CURRENT
        else -> TimeState.FUTURE
    }

    /**
     * 全天视图按日期过滤可见事件（1:1 iOS `ScheduleFullDayView.visibleEvents` L335-355）：
     * 过去日全部展示 / 未来日不剧透（空）/ 今天只显已开始。所有比较先经 [sortedEvents] 重排。
     */
    fun visibleEvents(
        events: List<ScheduleEventEntity>,
        selectedDayStart: Long,
        todayStart: Long,
        now: Long,
    ): List<ScheduleEventEntity> {
        val sorted = sortedEvents(events)
        return when {
            selectedDayStart < todayStart -> sorted
            selectedDayStart > todayStart -> emptyList()
            else -> sorted.filter { it.startTime <= now }
        }
    }

    /**
     * 全天视图的事件时态（1:1 iOS `ScheduleFullDayView.determineTimeState` L277-301）：
     * 历史日整日 PAST / 未来日整日 FUTURE / 今天走 [timelineTimeState] 的 now 细分。
     */
    fun fullDayTimeState(
        event: ScheduleEventEntity,
        selectedDayStart: Long,
        todayStart: Long,
        now: Long,
    ): TimeState = when {
        selectedDayStart < todayStart -> TimeState.PAST
        selectedDayStart > todayStart -> TimeState.FUTURE
        else -> timelineTimeState(event, now)
    }

    /**
     * 资料页今日卡的紧凑天气标签（1:1 iOS `compactWeatherLabel` L112-118）：
     * 有 emoji 且有 condition → 「城市 emoji条件」（城市为空则只剩「emoji条件」）；否则只显城市名；
     * 城市也为 null → 返回 null（整段不渲染）。当前安卓天气列恒 null（P11）→ 落到城市分支 → 通常 null。
     */
    fun compactWeatherLabel(cityName: String?, weatherEmoji: String?, weatherCondition: String?): String? {
        if (weatherEmoji != null && weatherCondition != null) {
            return listOfNotNull(cityName?.takeIf { it.isNotBlank() }, weatherEmoji + weatherCondition)
                .joinToString(" ")
        }
        return cityName?.takeIf { it.isNotBlank() }
    }

    /**
     * 事件活动文案（1:1 iOS `ScheduleEventRow.activityText` L112-116）：有同行角色名（trim 后非空）→
     * 「和「名字」活动」（中文直角引号「」）；否则纯活动文案。
     */
    fun activityText(activity: String, relatedCharacterNames: String?): String {
        val names = relatedCharacterNames?.trim().orEmpty()
        // [zCODE] P1·第2项 读取处2 顺带修（格式双写）：activity 已含同名人（如「与千岁在集市」）→ 不再前缀
        // 「和「千岁」」，杜绝「和「千岁」和千岁在集市」嵌套双写。多名单个命中即豁免（宁可少前缀不可双写）。
        val bareNames = names.replace(Regex("[「」，、,\\s]+"), "、").split("、").filter { it.isNotEmpty() }
        val alreadyMentioned = bareNames.any { activity.contains(it) } || activity.contains(names)
        return if (names.isEmpty() || alreadyMentioned) activity else "和「$names」$activity"
    }
}
