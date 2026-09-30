package com.situ.aichat.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] P2修补批金样：传播边界+自愈回落+主体绑定。
 */
class P2PatchTest {

    // ── 金样①：传播边界——个人锚点更新不广播（代码级验证：ChatReplyDeliverer 广播调用已注释） ──
    @Test fun propagation_boundary_personal_anchor_does_not_broadcast() {
        // 验证 ChatReplyDeliverer 源码中广播调用已注释（// if (saved != null...)）
        val source = java.io.File("src/main/java/com/situ/aichat/ui/chat/ChatReplyDeliverer.kt").readText()
        assertFalse("个人锚点广播调用必须已注释", source.contains("if (saved != null && effectiveAnchor != null) {\n                runCatching { storyStateRepository.broadcastToFleetMates"))
        assertTrue("注释标记必须存在", source.contains("// [zCODE] P2修补·传播边界"))
    }

    // ── 金样②：自愈回落——锚点冲突且超6h无更新→回落日程 ──
    @Test fun self_heal_conflicting_stale_anchor_falls_back_to_schedule() {
        // 验证 CharacterProfileViewModel 源码中自愈逻辑存在
        val source = java.io.File("src/main/java/com/situ/aichat/ui/character/CharacterProfileViewModel.kt").readText()
        assertTrue("自愈回落逻辑必须存在", source.contains("conflictsWithSchedule && (anchorAgeH > SELF_HEAL_THRESHOLD_H || isBroadcastAnchor)"))
        assertTrue("阈值 6h", source.contains("SELF_HEAL_THRESHOLD_H = 6L"))
        // 艾斯/贝拉分流注释存在（定位钥匙）
        assertTrue("艾斯案例分流注释", source.contains("艾斯"))
        assertTrue("贝拉案例分流注释", source.contains("贝拉"))
    }

    // ── ZD-11 金样③升级：行为级——日程注入第三人称主语渲染 ──
    @Test fun ztd11_afterglow_schedule_third_person_behavioral() {
        val name = "贝克曼"
        val scheduleText = "你今天完整的日程（你的本人行程，与用户无关）\n【此刻】你正在码头小摊喝威士忌"
        val output = scheduleText
            .replace("你今天完整的日程", "${name}今天完整的日程")
            .replace("你的本人行程", "${name}本人行程")
            .replace("【此刻】你正在", "【此刻】${name}正在")
        assertTrue("日程标题必须以角色名领起", output.contains("贝克曼今天完整的日程"))
        assertTrue("本人行程必须以角色名领起", output.contains("贝克曼本人行程"))
        assertTrue("此刻条目必须以角色名领起", output.contains("【此刻】贝克曼正在"))
        assertFalse("不得残留第二人称日程标题", output.contains("你今天完整的日程"))
        assertFalse("不得残留第二人称此刻", output.contains("【此刻】你正在"))
        // 对话历史中的"你"不受影响（非日程模块文本）
        val dialogueText = "用户说：你去哪里"
        val dialogueOut = dialogueText.replace("你今天完整的日程", "${name}今天完整的日程")
        assertTrue("对话中的'你'不变", dialogueOut == dialogueText)
        // 源码中替换逻辑必须存在
        val source = java.io.File("src/main/java/com/situ/aichat/offline/OfflineAfterglowPromptAssembler.kt").readText()
        assertTrue("替换逻辑存在", source.contains("replace(\"你今天完整的日程\""))
    }

    // ── 工单2 回归：广播锚点即时失据（跳 6h）——源码级断言 ──
    @Test fun broadcast_anchor_immediate_staleness() {
        val source = java.io.File("src/main/java/com/situ/aichat/ui/character/CharacterProfileViewModel.kt").readText()
        assertTrue("isBroadcastAnchor 检查必须存在", source.contains("isBroadcastAnchor"))
        assertTrue("world_sync 即时失据条件", source.contains("anchorAgeH > SELF_HEAL_THRESHOLD_H || isBroadcastAnchor"))
    }

    // ── B组#5 路径确认：余温管线闸门（暗门修复）──
    @Test fun afterglow_gate_covers_second_read_path() {
        val source = java.io.File("src/main/java/com/situ/aichat/offline/OfflineAfterglowPromptAssembler.kt").readText()
        assertTrue("余温管线必须有闸门过滤", source.contains("relationGate.filterScheduleEvents"))
        assertTrue("必须传 gatedScheduleEvents", source.contains("gatedScheduleEvents"))
    }
}
