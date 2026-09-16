package com.situ.aichat.offline

import android.util.Log

/**
 * [zCODE] LB-3-A·线下输出解析质量探针（只观察不施工修复）：线下叙事正文**零标签**（[叙述]/[对话] 等
 * offline 协议标记一个都没有——解析器按 1:1 iOS 全部落「叙述兜底」，渲染退化为裸混合文本）→ 记
 * offline_parse_fail 计数日志 + 样本前 40 字，不改用户界面。LB-3-A 实证为瞬态、重开自愈，先观察复现频率。
 * 回合级调用一次（ChatReplyDeliverer 投递前），不在渲染层反复触发。
 */
object OfflineParseQualityProbe {
    private const val TAG = "OfflineParse"
    private const val MIN_LENGTH_TO_REPORT = 20 // 太短的正文（单条短句）无标签属正常，不报

    /** offline 协议标签痕迹（开/闭任意形态）。 */
    private val TAG_TRACE = Regex("""\[\s*/?\s*(叙述|对话|动作|内心|情绪|环境|过渡|时间|场景)""")

    /** 日志出口（可注入——纯 JVM 单测替换断言，绕开 Robolectric 环境偶发 FileSystemAlreadyExists）。 */
    @Volatile
    internal var logSink: (tag: String, msg: String) -> Unit = { tag, msg -> Log.w(tag, msg) }

    fun probe(content: String) {
        val trimmed = content.trim()
        if (trimmed.length < MIN_LENGTH_TO_REPORT) return
        if (TAG_TRACE.containsMatchIn(trimmed)) return // 有任一标签 → 协议在场，不报
        logSink(TAG, "offline_parse_fail（零标签裸文本，全部落叙述兜底）sample=${trimmed.replace(Regex("[\\r\\n]+"), " ").take(40)}")
    }
}
