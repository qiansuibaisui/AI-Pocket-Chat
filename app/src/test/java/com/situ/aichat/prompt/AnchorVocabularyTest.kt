package com.situ.aichat.prompt

import com.situ.aichat.prompt.AnchorVocabulary.FreshnessLevel
import com.situ.aichat.prompt.AnchorVocabulary.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [zCODE] P1·第2项：节点命名规范化词表 + 时效分级单测（评审裁决1/4 定值锁定）。 */
class AnchorVocabularyTest {

    @Test fun normalize_sailing_family() {
        assertEquals(MotionState.SAILING, AnchorVocabulary.normalize("甲板上").state)
        assertEquals(MotionState.SAILING, AnchorVocabulary.normalize("航行中").state)
        assertEquals(MotionState.SAILING, AnchorVocabulary.normalize("在海上巡逻").state)
    }

    @Test fun normalize_docked_family() {
        assertEquals(MotionState.DOCKED, AnchorVocabulary.normalize("停泊在港口").state)
        assertEquals(MotionState.DOCKED, AnchorVocabulary.normalize("靠港补给").state)
    }

    @Test fun normalize_sea_beats_shore_inside_port_phrase() {
        // 优先级：先海后岸——「港口的酒馆」不落 ashore/indoors 抢判
        assertEquals(MotionState.DOCKED, AnchorVocabulary.normalize("港口边上的酒馆门口").state)
    }

    @Test fun normalize_ashore_and_indoors_and_transit() {
        assertEquals(MotionState.ASHORE, AnchorVocabulary.normalize("集市采买").state)
        assertEquals(MotionState.INDOORS, AnchorVocabulary.normalize("寝室里休息").state)
        assertEquals(MotionState.IN_TRANSIT, AnchorVocabulary.normalize("正在赶路").state)
    }

    @Test fun normalize_unknown_never_guesses() {
        val n = AnchorVocabulary.normalize("某个不知名的地方")
        assertEquals(MotionState.UNKNOWN, n.state)
        assertNull(n.key)
        assertEquals(MotionState.UNKNOWN, AnchorVocabulary.normalize("  ").state)
    }

    @Test fun normalize_key_is_matched_keyword() {
        assertEquals("甲板", AnchorVocabulary.normalize("甲板上").key)
    }

    @Test fun motion_state_raw_roundtrip() {
        // raw 即库契约：未知回退 UNKNOWN（不落进别的态）
        assertEquals(MotionState.SAILING, MotionState.fromRaw("sailing"))
        assertEquals(MotionState.UNKNOWN, MotionState.fromRaw("garbage"))
    }

    @Test fun freshness_thresholds_locked_6h_24h() {
        val now = 1_000_000_000_000L
        // 评审定值（可调·单源锁定）：≤6h FRESH / ≤24h AGING / >24h STALE
        assertEquals(FreshnessLevel.FRESH, AnchorVocabulary.freshnessOf(now - 6L * 3600_000, now))
        assertEquals(FreshnessLevel.AGING, AnchorVocabulary.freshnessOf(now - 6L * 3600_000 - 1, now))
        assertEquals(FreshnessLevel.AGING, AnchorVocabulary.freshnessOf(now - 24L * 3600_000, now))
        assertEquals(FreshnessLevel.STALE, AnchorVocabulary.freshnessOf(now - 24L * 3600_000 - 1, now))
        // 三天前的位置绝不会被判实时
        assertEquals(FreshnessLevel.STALE, AnchorVocabulary.freshnessOf(now - 72L * 3600_000, now))
    }
}
