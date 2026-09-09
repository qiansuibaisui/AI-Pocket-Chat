package com.situ.aichat.moments

import com.situ.aichat.prompt.AnchorVocabulary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [zCODE] P1·第2项 读取处3：海陆错配守卫（评审附加条件1——过去状语豁免 + fail-open）。 */
class AnchorConflictGuardTest {

    @Test fun sailing_anchor_vs_land_present_tense_conflicts() {
        assertTrue(AnchorConflictGuard.hasConflict("今天在集市逛了一天，买了好多芒果", AnchorVocabulary.MotionState.SAILING))
        assertTrue(AnchorConflictGuard.hasConflict("刚到酒馆坐下", AnchorVocabulary.MotionState.SAILING))
    }

    @Test fun past_adverb_nearby_is_exempt() {
        // 附加条件1b：过去式引用不误杀（"上周在集市买过芒果"是回忆，不是现在位置）
        assertFalse(AnchorConflictGuard.hasConflict("上周在集市买过芒果，现在还想着", AnchorVocabulary.MotionState.SAILING))
        assertFalse(AnchorConflictGuard.hasConflict("想起昨天在街道上遇到的那个孩子", AnchorVocabulary.MotionState.SAILING))
        assertFalse(AnchorConflictGuard.hasConflict("之前在城里住过一段时间", AnchorVocabulary.MotionState.SAILING))
    }

    @Test fun non_sailing_anchor_is_fail_open() {
        // 只管海↔陆；ashore/indoors/docked/unknown 锚点恒放行
        for (state in listOf(AnchorVocabulary.MotionState.ASHORE, AnchorVocabulary.MotionState.INDOORS, AnchorVocabulary.MotionState.DOCKED, AnchorVocabulary.MotionState.UNKNOWN, AnchorVocabulary.MotionState.IN_TRANSIT)) {
            assertFalse(AnchorConflictGuard.hasConflict("在集市逛街", state))
        }
    }

    @Test fun sailing_text_without_land_words_passes() {
        assertFalse(AnchorConflictGuard.hasConflict("甲板上的风真大，海鸥一直跟着船", AnchorVocabulary.MotionState.SAILING))
    }

    @Test fun exemption_window_is_local_not_global() {
        // 过去状语在远处（>12 字符）不豁免同句后方的现在时地点——窗口是局部的
        val text = "很久很久以前我们上个月的时候说过的话都算数，此刻我正走在喧闹的集市里"
        assertTrue(AnchorConflictGuard.hasConflict(text, AnchorVocabulary.MotionState.SAILING))
    }
}
