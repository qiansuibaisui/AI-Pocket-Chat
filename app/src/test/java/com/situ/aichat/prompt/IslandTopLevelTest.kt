package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P2·岛名规范与 locationKey 三层顶层（P3/P4 预埋·终审裁决③一期常量表）。
 *
 * 锁定：①顶层=规范岛名（八大域分组·包含式命中）；②"伟大航路"类域词**不映射任何域**（终审必改①——
 * 保留原文+调用侧 location_unnormalized 日志，fail-open）；③locationKey="{顶层}·{词表命中词}"，
 * 未规范顶层降级为仅命中词；④LB-4 不变（locationRaw 原始整值）。
 */
class IslandTopLevelTest {

    @Test fun normalized_top_level_from_domain_islands() {
        assertEquals("香波地", AnchorVocabulary.normalizeIslandTop("香波地群岛的港口"))
        assertEquals("和之国", AnchorVocabulary.normalizeIslandTop("雷德·佛斯号停靠和之国某海湾"))
        assertEquals("风车村", AnchorVocabulary.normalizeIslandTop("东海风车村"))
    }

    @Test fun grand_line_words_do_not_map_to_any_domain() {
        // 终审必改①：伟大航路是域词不是岛名——不映射"新世界"或任何域，null=未规范
        assertNull(AnchorVocabulary.normalizeIslandTop("伟大航路某岛"))
        assertNull(AnchorVocabulary.normalizeIslandTop("新世界的某个小岛附近")) // "新世界"本身不在岛名表
        assertFalse(AnchorVocabulary.isTopLevelNormalized("伟大航路某岛"))
    }

    @Test fun location_key_is_top_dot_keyword() {
        val n = AnchorVocabulary.normalize("香波地的酒馆")
        assertEquals(AnchorVocabulary.MotionState.ASHORE, n.state)
        assertEquals("香波地·酒馆", n.key)
    }

    @Test fun unnormalized_top_degrades_to_keyword_only() {
        val n = AnchorVocabulary.normalize("伟大航路某岛的集市")
        assertEquals("集市", n.key) // 未规范顶层 → 仅词表命中词（原文由调用侧日志）
    }

    @Test fun ship_name_is_mobile_island_top() {
        // 船名 = 移动岛屿同层索引：含船名时顶层=船名。一期船名不在岛名常量表——按裁决船名参与同层，
        // 最小集先行示例由词表命中 + 未规范日志兜底，P4 扩表时入列。
        assertNull(AnchorVocabulary.normalizeIslandTop("雷德·佛斯号"))
        assertTrue(AnchorVocabulary.normalize("雷德·佛斯号甲板").state == AnchorVocabulary.MotionState.SAILING)
    }
}
