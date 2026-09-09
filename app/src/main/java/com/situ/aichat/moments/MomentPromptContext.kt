package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.prompt.scheduleTimeOfDayLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 朋友圈生成 prompt 里的「此刻时间」与「今日日程素材」两段——1:1 iOS
 * `MomentGenerationService.buildNowContextPrompt(for:)` / `buildSchedulePrompt(for:context:)`。
 *
 * 与发帖正文模板（[MomentPromptStrings]，双语）不同，这两段在 iOS 是**硬编码中文**（LLM 读的产品资产，
 * 同 schedule/growth 约定），故这里也硬编码中文、不进 values。纯函数：时间/事件由参数给定，可不依赖设备单测。
 * post 与 comment（7.2.4）共用 [buildNowContext]。
 */
object MomentPromptContext {

    enum class NowScenario { POST, COMMENT }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private const val EVENT_TYPE_USER_INTERACTION = "userInteraction"
    private const val SOURCE_WEATHER_ADJUSTED = "weatherAdjusted"

    /**
     * 当前时间上下文（独立于日程，保证 LLM 永远知道真实时间）。post 用强约束版、comment 用柔和参考版。
     * 时段分桶复用 [scheduleTimeOfDayLabel]（与 iOS 朋友圈分桶 5..<9/9..<12/12..<14/14..<18/18..<22 完全一致）。
     */
    fun buildNowContext(scenario: NowScenario, nowMillis: Long, zone: ZoneId): String {
        val zdt = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val timeStr = HHMM.format(zdt)
        val dateLine = "${zdt.monthValue}月${zdt.dayOfMonth}日 ${weekday(zdt.dayOfWeek)}"
        val period = scheduleTimeOfDayLabel(zdt.hour)
        return when (scenario) {
            NowScenario.POST ->
                "【此刻的时间】\n" +
                    "今天是 $dateLine，现在是 $timeStr（$period）。\n" +
                    "内容必须符合此刻的时间段，不要写出与当前时间明显矛盾的场景（例如深夜时写晨光、清晨时写深夜辗转、上午时写晚安等）。"
            NowScenario.COMMENT ->
                "【此刻的时间参考】\n" +
                    "今天是 $dateLine，现在是 $timeStr（$period）。\n" +
                    "这只是时间参考，评论专注回应帖子内容即可——不必在评论里特意提到时间，也不用纠结自己的状态是否对得上此刻的时间段。"
        }
    }

    /**
     * 今日日程素材段（发帖用）。[events] = 今日日程的全部事件（调用方按角色+当天 0 点查好传入；日程系统关
     * 或无今日日程时调用方传空/不调用）。无可用行 → ""（时间已由 [buildNowContext] 独立注入）。
     *
     * 1:1 iOS：当前状态 + 下一项 + 过去「有趣事件」(有同伴/天气微调/非在家) 或最近 2 件作为素材 + 硬约束块。
     * 过滤 userInteraction（聊天写回/线下记录），与 `PromptBuilderSchedule` 一致。
     *
     * [zCODE] P1·第2项 读取处3：**当前状态行强制锚点优先**（fresh/aging 时系统登记锚点取代日程派生位置——
     * F1 根因切断：朋友圈位置不再来自生成的日程）；anchor null/超龄 → 回落日程现状（fail-open·老角色零波及）。
     * 无日程但有锚点 → 仍出锚点行（usable.isEmpty 不再短路）。
     */
    fun buildSchedulePromptText(
        events: List<ScheduleEventEntity>,
        nowMillis: Long,
        zone: ZoneId,
        characterName: String,
        anchor: com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity? = null,
    ): String {
        val usable = events.filter { it.eventTypeRaw != EVENT_TYPE_USER_INTERACTION }

        val lines = mutableListOf<String>()

        // [zCODE] 锚点优先的当前状态行（系统登记·真实位置）
        val anchorFreshEnough = anchor != null &&
            com.situ.aichat.prompt.AnchorVocabulary.freshnessOf(anchor.effectiveAt, nowMillis) !=
                com.situ.aichat.prompt.AnchorVocabulary.FreshnessLevel.STALE
        if (anchor != null && anchorFreshEnough) {
            val loc = anchor.locationRaw.ifBlank { "位置未记录" }
            var line = "【当前状态·系统登记】${characterName}当前：${anchor.eventName.ifBlank { "在$loc" }}"
            if (anchor.eventName.isNotBlank()) line += "（在$loc）"
            lines.add(line)
        }

        // 当前活动（日程派生·仅锚点缺失/超龄时作为当前状态行；否则降为「日程计划」参考行）
        val current = usable.firstOrNull { it.startTime <= nowMillis && nowMillis <= it.endTime }
        if (current != null) {
            if (!anchorFreshEnough) {
                var line = "【当前状态】${characterName}正在：${current.activity}"
                if (current.location.isNotEmpty()) line += "（在${current.location}）"
                current.moodText?.takeIf { it.isNotEmpty() }?.let { line += "，心情：$it" }
                lines.add(line)
            } else {
                lines.add("（日程计划参考）这个时段原本安排：${current.activity}")
            }
        }

        // 下一个活动（moments-logic-2：对齐 iOS CharacterDailySchedule.sortedEvents 的 (sortOrder, startTime) 次序，
        // futureEvents.first = 最小 sortOrder 的未来事件，与下方过去事件 line 83 同口径；纯 prompt 注入次序，不涉数值）
        val next = usable.filter { it.startTime > nowMillis }
            .sortedWith(compareBy({ it.sortOrder }, { it.startTime }))
            .firstOrNull()
        if (next != null) lines.add("接下来要：${next.activity}")

        // 过去的事件（作为朋友圈素材）：有趣（有同伴/天气微调/非在家）优先，否则最近 2 件
        val pastEvents = usable
            .filter { it.endTime <= nowMillis }
            .sortedWith(compareBy({ it.sortOrder }, { it.startTime }))
        if (pastEvents.isNotEmpty()) {
            val interesting = pastEvents.filter { e ->
                val hasCompanion = !e.relatedCharacterNames.isNullOrEmpty()
                val isWeatherAdjusted = e.sourceRaw == SOURCE_WEATHER_ADJUSTED
                val isOutdoor = e.location != "家里" && e.location != "家"
                hasCompanion || isWeatherAdjusted || isOutdoor
            }
            val selected = interesting.ifEmpty { pastEvents.takeLast(2) }
            if (selected.isNotEmpty()) {
                lines.add("今天${characterName}做了这些事，可以作为朋友圈素材：")
                for (e in selected) {
                    var line = "- ${e.periodLabel} ${e.activity}"
                    if (e.location.isNotEmpty()) line += "（在${e.location}）"
                    e.relatedCharacterNames?.takeIf { it.isNotEmpty() }?.let { line += "，和${it}一起" }
                    lines.add(line)
                }
            }
        }

        if (lines.isEmpty()) return ""

        // 硬约束：朋友圈必须与日程状态一致
        lines.add("")
        lines.add("【重要约束】你发的朋友圈必须与你当前的日程状态一致：")
        if (anchorFreshEnough) lines.add("- 你的实际位置以上方【当前状态·系统登记】为准，日程只是计划参考")
        lines.add("- 你只能发与你正在做的事、刚做完的事、或此刻的心情相关的内容")
        lines.add("- 不要发与你当前时间和活动明显矛盾的内容（比如你在上班却发在家的内容）")
        lines.add("- 优先挑一件有趣、具体、值得分享的事来发，不要逐条复述行程")

        return lines.joinToString("\n")
    }

    private fun weekday(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }
}
