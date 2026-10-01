package com.situ.aichat.moments

import com.situ.aichat.schedule.RelationGateService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] 中继3·项5(v2)·禁令二金样：单向知晓/陌生人不得朋友圈互动（治 B6/F2·认知边界三.②）；
 * 用户动态不拦（质感评分既有管辖）；闸门异常 fail-open。
 */
class MomentCognitionGateTest {

    private val relationGate = mockk<RelationGateService>(relaxed = true)
    private val gate = MomentCognitionGate(relationGate)

    @Test fun user_post_bypasses_cognition_check() = runTest {
        assertTrue("作者=用户（null）→ 放行（质感评分管辖）", gate.interactionAllowed("c1", null))
        assertTrue(gate.interactionAllowed("c1", ""))
        coVerify(exactly = 0) { relationGate.levelBetween(any(), any()) }
    }

    @Test fun one_way_awareness_blocked_for_character_post() = runTest {
        coEvery { relationGate.levelBetween("c1", "author") } returns RelationGateService.Level.AWARE_ONEWAY
        assertFalse("单向知晓不得朋友圈互动（认知边界三.②）", gate.interactionAllowed("c1", "author"))
    }

    @Test fun stranger_blocked_for_character_post() = runTest {
        coEvery { relationGate.levelBetween("c1", "author") } returns RelationGateService.Level.STRANGER
        assertFalse("零级禁互动", gate.interactionAllowed("c1", "author"))
    }

    @Test fun mutual_awareness_allows_low_freq_interaction() = runTest {
        coEvery { relationGate.levelBetween("c1", "author") } returns RelationGateService.Level.AWARE_MUTUAL
        assertTrue("双向知晓可低频点赞/评论", gate.interactionAllowed("c1", "author"))
    }

    @Test fun gate_failure_fails_open() = runTest {
        coEvery { relationGate.levelBetween("c1", "author") } throws IllegalStateException("db")
        assertTrue("闸门异常 fail-open（与 A5 同口径·不阻既有互动链）", gate.interactionAllowed("c1", "author"))
    }
}
