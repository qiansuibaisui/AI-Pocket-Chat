package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.AnchorSource
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P1·第3项 锚点格式**正式契约金样**（分隔符 ·/｜/： 锁定·与《锚点格式》规范 v1 同源）。
 *
 * 三形态归一断言：①存量块式（【场景状态】键值）；②规范单行（【锚点】{个人·基线}｜{名}：在场/不在场（位置））；
 * ③简式（[场景：x·y]）。LB-4 规格澄清锁死：位置字段 = 原始整值（值内 · 如船名"雷德·佛斯号"绝不切分），
 * · 仅用于 locationKey 层的船团基线前缀比较——全链（解析→落库字段→"当前"行显示）按整值断言。
 */
class AnchorContractGoldenTest {

    // ── 规范单行形态：分隔符契约 + 在场名单 ──

    @Test fun canonical_single_line_full_form() {
        val b = AnchorBlockParser.parseLastBlock("————【锚点】甲板·白团海上旗舰｜贝克曼：不在场（酒馆）｜艾斯：在场")
        assertEquals("甲板·白团海上旗舰", b!!.locationRaw) // 整值（个人·基线原始串，· 不切分）
        assertEquals(2, b.presentList.size)
        assertEquals("贝克曼", b.presentList[0].name)
        assertEquals(false, b.presentList[0].present)
        assertEquals("酒馆", b.presentList[0].location)
        assertEquals("艾斯", b.presentList[1].name)
        assertEquals(true, b.presentList[1].present)
    }

    @Test fun canonical_no_present_list() {
        val b = AnchorBlockParser.parseLastBlock("【锚点】街道·港口镇")
        assertEquals("街道·港口镇", b!!.locationRaw)
        assertTrue(b.presentList.isEmpty())
    }

    // ── LB-4：地点值含 ·（船名）全链——解析→落库字段→"当前"行显示 均整值 ──

    @Test fun lb4_dot_inside_location_value_full_chain() {
        // ① 解析：整值不切分
        val b = AnchorBlockParser.parseLastBlock("————【锚点】雷德·佛斯号甲板·夜航")
        assertEquals("雷德·佛斯号甲板·夜航", b!!.locationRaw)
        // ② 落库字段语义（Repository 落库即 AnchorBlock.locationRaw 原样入 locationRaw 列）+ 归一：
        //    词表命中"甲板"→ sailing（key=甲板，整值内的 · 不影响命中）
        val n = AnchorVocabulary.normalize(b.locationRaw)
        assertEquals(AnchorVocabulary.MotionState.SAILING, n.state)
        // ③ "当前"行显示（InjectionBuilder·LB-4 后 eventName 空整值直出·无括号重组）
        val now = 1_000_000L
        val snap = StoryAnchorSnapshotEntity(
            characterUuid = "c1", eventName = "", locationRaw = b.locationRaw,
            motionStateRaw = n.state.raw, sourceRaw = AnchorSource.DIALOG_BLOCK.raw,
            effectiveAt = now, capturedAt = now,
        )
        val module = AnchorInjectionBuilder.buildModule(snap, emptyList(), now)
        assertTrue("显示行必须含原始整值（· 不重组）: $module", module.contains("雷德·佛斯号甲板·夜航"))
        assertTrue(module.contains("刚刚更新"))
    }

    // ── 三形态并存：最后输出优先（模型最终落点） ──

    @Test fun three_forms_last_wins() {
        val text = "[场景：酒馆]早期\n【场景状态】\n地点：甲板\n中期\n————【锚点】街道·最终形态"
        val b = AnchorBlockParser.parseLastBlock(text)
        assertEquals("街道·最终形态", b!!.locationRaw) // 块式在中、规范单行在后 → 取最后
    }

    // ── 简式（[场景：x·y]）既有协议不受 LB-4 影响（· 切分为该形态自身协议） ──

    @Test fun legacy_inline_form_unchanged() {
        val b = AnchorBlockParser.parseLastBlock("[场景：港口·补给装卸中·晨]正文")
        assertEquals("港口", b!!.locationRaw)
        assertEquals("补给装卸中", b.eventName)
        assertEquals("晨", b.timeText)
    }
}
