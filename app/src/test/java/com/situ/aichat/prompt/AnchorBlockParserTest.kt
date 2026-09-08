package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [zCODE] P1·第2项：对话内锚点块解析单测（写入通道①·容错层复用验证）。 */
class AnchorBlockParserTest {

    @Test fun inline_tag_basic_with_time() {
        val b = AnchorBlockParser.parseLastBlock("[场景：甲板·黄昏]今晚风很大。")
        assertNotNull(b)
        assertEquals("甲板", b!!.locationRaw)
        assertEquals("黄昏", b.timeText)
    }

    @Test fun inline_tag_event_middle() {
        // 现实格式 = 地点·事件·时间（时间恒在末段·对齐线下叙事 [场景：地点·时间]）
        val b = AnchorBlockParser.parseLastBlock("[场景：港口·补给装卸中·晨]水手们忙成一团")
        assertEquals("港口", b!!.locationRaw)
        assertEquals("补给装卸中", b.eventName)
        assertEquals("晨", b.timeText)
    }

    @Test fun inline_tag_without_time() {
        val b = AnchorBlockParser.parseLastBlock("[场景：酒馆]热闹得很")
        assertEquals("酒馆", b!!.locationRaw)
        assertNull(b.timeText)
    }

    @Test fun block_header_key_value_form() {
        val text = """
            好的，我知道了。
            【场景状态】
            地点：甲板
            事件：守夜
        """.trimIndent()
        val b = AnchorBlockParser.parseLastBlock(text)
        assertEquals("甲板", b!!.locationRaw)
        assertEquals("守夜", b.eventName)
    }

    @Test fun block_header_single_line_form() {
        val b = AnchorBlockParser.parseLastBlock("【场景状态】甲板上\n正文另起")
        assertEquals("甲板上", b!!.locationRaw)
    }

    @Test fun last_block_wins() {
        val b = AnchorBlockParser.parseLastBlock("[场景：酒馆]先在这，后来 [场景：街道·夜] 出门了")
        assertEquals("街道", b!!.locationRaw)
    }

    @Test fun malformed_never_throws_and_degrades() {
        // 畸形（空段/裸标记/杂符号）——容错层降级，绝不炸回合
        assertNull(AnchorBlockParser.parseLastBlock("[场景：]"))
        assertNull(AnchorBlockParser.parseLastBlock("[场景：   ]废话"))
        assertNull(AnchorBlockParser.parseLastBlock("完全没有锚点的普通回复"))
        assertEquals("甲板", AnchorBlockParser.parseLastBlock("前半[场景：·]坏掉的 [场景：甲板]好的")!!.locationRaw)
    }

    @Test fun multiple_extract_all() {
        val all = AnchorBlockParser.parseBlocks("[场景：酒馆]一段 [场景：港口·午]二段")
        assertEquals(2, all.size)
        assertEquals(listOf("酒馆", "港口"), all.map { it.locationRaw })
    }
}
