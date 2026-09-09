package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** [zCODE] P1·第2项 读取处3：朋友圈位置源锚点优先（F1 根因切断——位置不再来自日程派生）。 */
class MomentPromptContextAnchorTest {

    private val zone = ZoneId.systemDefault()
    private val now = 1_000_000L

    private fun anchor(effectiveAt: Long, loc: String = "甲板", event: String = "守夜") =
        StoryAnchorSnapshotEntity(characterUuid = "c1", locationRaw = loc, eventName = event, motionStateRaw = "sailing", effectiveAt = effectiveAt, capturedAt = now)

    @Test fun anchor_first_current_state_replaces_schedule_line() {
        val text = MomentPromptContext.buildSchedulePromptText(emptyList(), now, zone, "贝克曼", anchor(now - 3600_000))
        assertTrue(text.contains("【当前状态·系统登记】贝克曼当前：守夜（在甲板）"))
        assertTrue(text.contains("实际位置以上方【当前状态·系统登记】为准"))
    }

    @Test fun anchor_present_even_without_schedule() {
        // 无日程但有锚点 → 仍出位置行（usable.isEmpty 不再短路）
        val text = MomentPromptContext.buildSchedulePromptText(emptyList(), now, zone, "贝克曼", anchor(now))
        assertFalse(text.isEmpty())
    }

    @Test fun stale_anchor_falls_back_to_schedule_line() {
        // 超龄锚点 → 回落日程派生【当前状态】（fail-open）
        val events = listOf(
            com.situ.aichat.data.local.entity.ScheduleEventEntity(
                uuid = "e1", scheduleUuid = "s1", startTime = now - 100, endTime = now + 100,
                activity = "睡觉", location = "寝室",
            ),
        )
        val text = MomentPromptContext.buildSchedulePromptText(events, now, zone, "贝克曼", anchor(now - 72L * 3600_000))
        assertTrue(text.contains("【当前状态】贝克曼正在：睡觉"))
        assertFalse(text.contains("系统登记"))
    }

    @Test fun no_anchor_keeps_legacy_behavior() {
        val events = listOf(
            com.situ.aichat.data.local.entity.ScheduleEventEntity(
                uuid = "e1", scheduleUuid = "s1", startTime = now - 100, endTime = now + 100,
                activity = "喝酒", location = "酒馆",
            ),
        )
        val text = MomentPromptContext.buildSchedulePromptText(events, now, zone, "贝克曼", null)
        assertTrue(text.contains("【当前状态】贝克曼正在：喝酒"))
    }

    @Test fun nothing_at_all_renders_empty() {
        assertTrue(MomentPromptContext.buildSchedulePromptText(emptyList(), now, zone, "x", null).isEmpty())
    }
}
