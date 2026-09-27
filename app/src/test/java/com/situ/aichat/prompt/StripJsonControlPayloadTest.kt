package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] LB-6b：独立成行 JSON 控制载荷剥除金样（P2 批一出包前置·LB-6 标签形态的 JSON 变体）。
 *
 * 五组断言：①独立行载荷→剥净；②正文中间 JSON→保留；③既有 [...] 标签剥离不回归；④连续载荷+空行→全剥无堆积；⑤非控制字段 JSON→保留。
 */
class StripJsonControlPayloadTest {

    // ── 金样 1：独立行载荷 → 剥净，前后无缝 ──

    @Test fun standalone_json_payload_stripped() {
        val input = "见面很开心。\n{\"type\":\"offline_end\",\"finalMood\":\"warm\"}\n期待下次。"
        val out = ReplyParser.stripInternalAssistantTags(input)
        assertEquals("见面很开心。\n期待下次。", out)
    }

    // ── 金样 2（反例）：正文中间 JSON 字样 → 原样保留 ──

    @Test fun json_embedded_in_prose_preserved() {
        val input = "他说「配置文件里写的是 {\"type\":\"config\",\"value\":42} 这样」然后笑了。"
        val out = ReplyParser.stripInternalAssistantTags(input)
        assertTrue(out.contains("{\"type\":\"config\",\"value\":42}"))
    }

    // ── 金样 3（反例）：既有标签语法 [...] 不回归 ──

    @Test fun legacy_tag_stripping_not_regressed() {
        val input = "正文。[/对话] 尾巴[/叙述]"
        val out = ReplyParser.stripInternalAssistantTags(input)
        assertFalse(out.contains("[/对话]"))
        assertFalse(out.contains("[/叙述]"))
        assertTrue(out.contains("正文"))
    }

    // ── 金样 4：连续两行载荷 + 空行混杂 → 全剥无空行堆积 ──

    @Test fun consecutive_payloads_and_blank_lines_all_stripped() {
        val input = "开场。\n{\"type\":\"offline_end\",\"finalMood\":\"warm\"}\n{\"action\":\"leave\",\"location\":\"门口\"}\n\n结尾。"
        val out = ReplyParser.stripInternalAssistantTags(input)
        assertEquals("开场。\n\n结尾。", out)
    }

    // ── 金样 5（反例）：非控制字段 JSON 行 → 保留（防正文 JSON 代码块误杀） ──

    @Test fun non_control_json_preserved() {
        val input = "他念了一段诗：\n{\"title\":\"夜曲\",\"author\":\"某某\",\"year\":\"1935\"}"
        val out = ReplyParser.stripInternalAssistantTags(input)
        assertTrue(out.contains("{\"title\":\"夜曲\""))
    }
}
