package com.situ.aichat.offline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] LB-3-A/③·解析失败日志断言：线下正文解析为空块 → offline_parse_fail 计数日志（样本前 40 字），
 * 不改用户界面。日志出口可注入（logSink）→ 纯 JVM 断言（绕开 Robolectric 环境偶发 FileSystemAlreadyExists）。
 */
class OfflineParseQualityProbeTest {

    @After fun restoreSink() {
        OfflineParseQualityProbe.logSink = { _, _ -> } // 测试后还原（默认实现引用 android.util.Log，纯 JVM 下不可触）
    }

    @Test fun empty_block_result_logs_warning_with_sample() {
        val captured = mutableListOf<Pair<String, String>>()
        OfflineParseQualityProbe.logSink = { tag, msg -> captured.add(tag to msg) }
        // 零标签长文本 → 协议失守（解析器全落叙述兜底）→ 探针记 offline_parse_fail
        OfflineParseQualityProbe.probe("这是一段完全没有叙事标签的线下正文，长度超过阈值，全是裸文本。")
        assertEquals(1, captured.size)
        assertEquals("OfflineParse", captured.first().first)
        assertTrue(captured.first().second.contains("offline_parse_fail"))
        assertTrue(captured.first().second.contains("sample="))
    }

    @Test fun tagged_content_and_short_input_stay_silent() {
        val captured = mutableListOf<Pair<String, String>>()
        OfflineParseQualityProbe.logSink = { tag, msg -> captured.add(tag to msg) }
        // 合法叙事标签 → 解析非空，不报
        OfflineParseQualityProbe.probe("[叙述]夜色沉下来，两人并肩走着，码头的灯一盏盏亮起。")
        // 过短正文（<20 字）→ 不报（短句解析为空属正常）
        OfflineParseQualityProbe.probe("嗯。")
        assertEquals(0, captured.size)
    }
}
