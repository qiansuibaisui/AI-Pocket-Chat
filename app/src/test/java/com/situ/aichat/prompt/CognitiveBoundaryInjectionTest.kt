package com.situ.aichat.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P4·前置件 0 金样：认知边界六条（每条 1 例措辞锚点 + 1 例错字修正回归 = 7 例）。
 */
class CognitiveBoundaryInjectionTest {

    @Test fun condition1_basic_judgment_anchor() {
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("互为陌生人"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("队友圈"))
    }

    @Test fun condition2_innate_relations_anchor() {
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("先天关系"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("名声≠私交"))
    }

    @Test fun condition3_hierarchy_anchor() {
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("权限逐级累加"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("① 队友"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("② 知晓"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("③ 新识"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("④ 旧识"))
    }

    @Test fun condition4_news_equals_relay_anchor() {
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("新闻推送视同转报"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("幕后事件"))
    }

    @Test fun condition5_registration_is_bookkeeping_anchor() {
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("登记是记账而非生效条件"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("怀迪贝（知晓·A电话虫转报"))
    }

    @Test fun condition6_generation_redline_anchor() {
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("生成红线"))
        assertTrue(CognitiveBoundaryInjection.BLOCK.contains("无靠港记录不得出现陆地场景"))
    }

    @Test fun foster_daughter_entry_resolves_cognitive_boundary_reference() {
        assertTrue(FosterDaughterEntry.BLOCK.contains("白胡子的养女"))
        assertTrue(FosterDaughterEntry.BLOCK.contains("认知分层规则"))
        assertTrue(FosterDaughterEntry.BLOCK.contains("①"))
        assertTrue(FosterDaughterEntry.BLOCK.contains("②"))
        assertTrue(FosterDaughterEntry.BLOCK.contains("③"))
    }

    @Test fun fullwidth_bracket_typo_fixed() {
        // 正本一.1 自带 {{user}｝ 全角闭括号 → 誊入时统一修为半角 {{user}}
        assertFalse("不得残留全角闭括号 ｝", CognitiveBoundaryInjection.BLOCK.contains("｝"))
        assertTrue("半角 {{user}} 必须存在", CognitiveBoundaryInjection.BLOCK.contains("{{user}}"))
    }
}
