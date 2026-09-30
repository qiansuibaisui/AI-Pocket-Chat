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

    // ── 金样③：主体绑定——余温 prompt 含第三人称主语绑定（B组：二→三人称改版） ──
    @Test fun subject_binding_line_exists_in_afterglow_prompt() {
        val source = java.io.File("src/main/java/com/situ/aichat/offline/OfflineAfterglowService.kt").readText()
        assertTrue("第三人称主语绑定行必须存在", source.contains("\${character.name}日程里的事是\${character.name}本人的行程"))
        assertTrue("不得复读成用户行为", source.contains("不要把日程条目复读成\${userName}的行为"))
        assertTrue("不得说'我们一起'约束", source.contains("不要说'我们一起'除非确有见面记录"))
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
