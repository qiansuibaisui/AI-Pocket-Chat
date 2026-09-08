package com.situ.aichat.data.remote.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P1·解析容错层单测（OutputSanitizer）。
 *
 * 锁定三件事：
 * 1. 泄漏闭合标签（MiniMax `[/dialogue]` 实测样本类）全量/流式两种模式都剥干净、计数正确；
 * 2. 流式跨 chunk 半标签（`[/dialo` + `gue]`）不漏剥不误留，flush 的截断残留（流中断悬尾）丢弃；
 * 3. 容错块解析 [OutputSanitizer.parseBlockTolerantly]（第 2 项锚点块复用接口）：null/抛异常/返空 → Degraded，绝不抛。
 */
class OutputSanitizerTest {

    // ── sanitizeFull（非流式，completion 统一出口） ──

    @Test fun full_strips_leaked_closing_tags() {
        val result = OutputSanitizer.sanitizeFull("走远了。[/dialogue]夜色沉下来。")
        assertEquals("走远了。夜色沉下来。", result.text)
        assertEquals(1, result.removedCount)
    }

    @Test fun full_strips_multiple_and_variants() {
        val result = OutputSanitizer.sanitizeFull("[/dialogue]开头[/scene]中段[/narration]结尾")
        assertEquals("开头中段结尾", result.text)
        assertEquals(3, result.removedCount)
    }

    @Test fun full_keeps_normal_text_and_brackets() {
        val input = "他说[这不是标记]，也说了[a]和[/]。" // [/] 无名不匹配；[这不是标记] 非闭合形态
        val result = OutputSanitizer.sanitizeFull(input)
        assertEquals("他说[这不是标记]，也说了[a]和[/]。", result.text)
        assertEquals(0, result.removedCount)
    }

    @Test fun full_strips_truncated_trailing_tag() {
        // 流中断留下的悬尾半个标签（无 ] 收口）
        val result = OutputSanitizer.sanitizeFull("正文到这里被掐断[/dialo")
        assertEquals("正文到这里被掐断", result.text)
        assertEquals(1, result.removedCount)
    }

    @Test fun full_json_payload_passthrough() {
        // 后台任务的 JSON 产物不含 [/name] 形态 → 零波及（评审裁决①的安全性前提）
        val json = """{"mood":"好","scores":{"a":1},"text":"含[方括号]正文"}"""
        val result = OutputSanitizer.sanitizeFull(json)
        assertEquals(json, result.text)
        assertEquals(0, result.removedCount)
    }

    @Test fun full_protocol_markers_unaffected() {
        // App 自有协议标记无 [/name] 闭合形态 → 不受清洗影响（由 AssistantResponsePreprocessor 消费）
        val input = "[CALENDAR_ACTION]{\"a\":1} 正文 [future_meeting]{\"b\":2} 尾"
        val result = OutputSanitizer.sanitizeFull(input)
        assertEquals(input, result.text)
        assertEquals(0, result.removedCount)
    }

    // ── StreamSanitizer（流式增量，挂在 Content token 上） ──

    @Test fun stream_strips_tag_within_single_chunk() {
        val s = OutputSanitizer.StreamSanitizer()
        val out = (s.parse("你好[/dialogue]呀") + listOf(s.flush())).joinToString("")
        assertEquals("你好呀", out)
        assertEquals(1, s.removedCount)
    }

    @Test fun stream_handles_tag_split_across_chunks() {
        val s = OutputSanitizer.StreamSanitizer()
        val first = s.parse("夜色沉下来，[/dialo")
        val second = s.parse("gue]他没回头。")
        val tail = s.flush()
        val all = (first + second + listOf(tail)).joinToString("")
        assertEquals("夜色沉下来，他没回头。", all)
        assertEquals(1, s.removedCount)
    }

    @Test fun stream_flush_drops_truncated_leak_tail() {
        // 流中断：缓冲区只剩半个泄漏标签 → flush 丢弃并计数（不让 `[/dia` 漏进气泡）
        val s = OutputSanitizer.StreamSanitizer()
        val out = s.parse("正文完了[/dia")
        assertEquals("正文完了", out.joinToString(""))
        assertEquals("", s.flush())
        assertEquals(1, s.removedCount)
    }

    @Test fun stream_flush_returns_plain_remainder_verbatim() {
        // 收尾残留是普通正文（非标签形态）→ 原样交还，绝不吃字
        val s = OutputSanitizer.StreamSanitizer()
        s.parse("最后一句没打完的")
        assertEquals("最后一句没打完的", s.flush())
        assertEquals(0, s.removedCount)
    }

    @Test fun stream_empty_chunks_are_noop() {
        val s = OutputSanitizer.StreamSanitizer()
        assertEquals(emptyList<String>(), s.parse(""))
        assertEquals("", s.flush())
        assertEquals(0, s.removedCount)
    }

    // ── parseBlockTolerantly（第 2 项锚点块容错接口） ──

    @Test fun block_null_input_degrades() {
        val result = OutputSanitizer.parseBlockTolerantly<String>(null, "test") { it }
        assertTrue(result is OutputSanitizer.BlockParseResult.Degraded)
    }

    @Test fun block_throwing_extractor_degrades() {
        val result = OutputSanitizer.parseBlockTolerantly("garbage", "test") { throw IllegalStateException("bad json") }
        assertTrue(result is OutputSanitizer.BlockParseResult.Degraded)
        assertTrue(
            (result as OutputSanitizer.BlockParseResult.Degraded).reason.contains("IllegalStateException"),
        )
    }

    @Test fun block_null_result_degrades() {
        val result = OutputSanitizer.parseBlockTolerantly<String>("输入正常但解析器返空", "test") { null }
        assertTrue(result is OutputSanitizer.BlockParseResult.Degraded)
    }

    @Test fun block_ok_value_passes_through() {
        val result = OutputSanitizer.parseBlockTolerantly("ok", "test") { "parsed" }
        assertEquals("parsed", (result as OutputSanitizer.BlockParseResult.Ok).value)
    }
}
