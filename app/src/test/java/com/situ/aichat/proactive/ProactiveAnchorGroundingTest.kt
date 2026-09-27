package com.situ.aichat.proactive

import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P2·切片二（预置二：任务三测试先验不省）：主动消息锚点接地直接断言。
 */
class ProactiveAnchorGroundingTest {

    private fun anchor(effectiveAt: Long, loc: String = "甲板", motion: String = "sailing") =
        StoryAnchorSnapshotEntity(characterUuid = "c1", locationRaw = loc, motionStateRaw = motion, effectiveAt = effectiveAt, capturedAt = effectiveAt)

    @Test fun fresh_sailing_anchor_injects_position_and_sea_hint() {
        val now = 1_000_000L
        val text = ProactiveAnchorGrounding.build(anchor(now - 3600_000), "贝克曼", now)
        assertTrue(text.contains("贝克曼当前在甲板"))
        assertTrue(text.contains("海上航行"))
        assertTrue(text.contains("不要说在酒馆"))
        assertTrue(text.contains("以这里为准"))
    }

    @Test fun docked_anchor_has_port_hint_without_sea_forbid() {
        val now = 1_000_000L
        val text = ProactiveAnchorGrounding.build(anchor(now - 3600_000, loc = "香波地码头", motion = "docked"), "艾斯", now)
        assertTrue(text.contains("停泊在港口"))
        assertEquals(false, text.contains("不要说在酒馆"))
    }

    @Test fun stale_or_missing_anchor_omits_grounding_fail_open() {
        val now = 1_000_000L
        assertEquals("", ProactiveAnchorGrounding.build(null, "x", now))
        assertEquals("", ProactiveAnchorGrounding.build(anchor(now - 72L * 3600_000), "x", now)) // 超龄
        assertEquals("", ProactiveAnchorGrounding.build(anchor(now, loc = ""), "x", now)) // 空位置
    }
}
