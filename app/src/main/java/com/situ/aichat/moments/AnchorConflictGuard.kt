package com.situ.aichat.moments

import com.situ.aichat.prompt.AnchorVocabulary

/**
 * [zCODE] P1·第2项 读取处3：朋友圈海陆错配守卫（纯函数）。
 *
 * 判定：锚点 MotionState 为海上（sailing）而帖子文本出现**陆地/室内现在时**位置词 → 冲突。
 * 评审附加条件1 的两条防护：
 * - a) 拒发仅统计到日志（调用方记数 + 样本），上线后看误杀率——本守卫只回答「冲不冲突」，处置在调用方；
 * - b) **过去时间状语豁免**（fail-open 一致性）：冲突词前方 12 字符内出现「上周/昨天/之前/上次/那天/那次/前天」
 *   等过去状语 → 视为回忆性引用（"上周在集市买过芒果"），豁免——宁可放过一条，不可错杀。
 *
 * 只管「海 ↔ 陆」一对（最强语义冲突）；docked↔ashore（港口与岸）等弱冲突不判，避免误杀。
 */
object AnchorConflictGuard {

    private val LAND_NOW_WORDS = Regex("集市|街道|镇上|城里|广场|酒馆|店里|商场|家中|寝室|房间里")

    /** 过去时间状语（豁免窗口内的命中不算冲突）。 */
    private val PAST_ADVERB = Regex("上周|上週|昨天|昨日|之前|以前|上次|那天|那次|前天|刚刚那会儿|回忆|想起")

    /** 豁免窗口：冲突词前方向前看多少字符内存在过去状语即豁免。 */
    private const val EXEMPTION_WINDOW_CHARS = 12

    /** 文本与锚点状态是否冲突。anchorState 非 sailing 恒 false（fail-open）。 */
    fun hasConflict(text: String, anchorState: AnchorVocabulary.MotionState): Boolean {
        if (anchorState != AnchorVocabulary.MotionState.SAILING) return false
        var searchFrom = 0
        while (true) {
            val m = LAND_NOW_WORDS.find(text, searchFrom) ?: return false
            if (!isNearPastAdverb(text, m.range.first)) return true
            searchFrom = m.range.last + 1
        }
    }

    /** 冲突词前方窗口内是否有过去状语（附加条件1b：简单规则，宁可放过）。 */
    private fun isNearPastAdverb(text: String, matchStart: Int): Boolean {
        val windowStart = (matchStart - EXEMPTION_WINDOW_CHARS).coerceAtLeast(0)
        return PAST_ADVERB.containsMatchIn(text.substring(windowStart, matchStart))
    }
}
