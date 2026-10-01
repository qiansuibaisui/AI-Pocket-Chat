package com.situ.aichat.notification

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.morgans.NewsControlSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] 中继3·项5(v2)·C11 发射闸金样：禁令一（陌生卡不得发起·治 F3）/人设沉默兜底（参数位）/
 * 情报型 bypass（发射闸检查点·默认空表零 bypass）/默认关=现行为（拍板③）。
 */
class ProactiveLaunchGateTest {

    private val controlSettings = mockk<NewsControlSettings>(relaxed = true)
    private val gate = ProactiveLaunchGate(controlSettings)

    private fun strangerCard() = CharacterEntity(uuid = "s1", name = "新卡", creationDate = 0L) // relationshipQualityJSON 默认空

    private fun acquaintedCard(json: String = """{"familiarity":40,"trust":50}""") =
        CharacterEntity(uuid = "a1", name = "老友", creationDate = 0L, relationshipQualityJSON = json)

    @Test fun gate_disabled_by_default_keeps_current_behavior() = runTest {
        coEvery { controlSettings.proactiveGate() } returns NewsControlSettings.ProactiveGate(enabled = false)
        assertTrue("默认关=现行为（拍板③）", gate.canLaunch(strangerCard()).allowed)
    }

    @Test fun stranger_card_blocked_when_enabled() = runTest {
        coEvery { controlSettings.proactiveGate() } returns NewsControlSettings.ProactiveGate(enabled = true)
        val decision = gate.canLaunch(strangerCard())
        assertFalse("禁令一：陌生卡（质感空列）不得发起", decision.allowed)
        assertTrue(decision.reason.contains("陌生"))
    }

    @Test fun initial_baseline_quality_is_still_stranger() = runTest {
        coEvery { controlSettings.proactiveGate() } returns NewsControlSettings.ProactiveGate(enabled = true)
        // 建卡后零互动改写：JSON 仍为 INITIAL 基线（八维默认值·"fun" 为 iOS 键名）→ 陌生
        val initialJson = """{"familiarity":10,"trust":20,"closeness":10,"rapport":10,"respect":35,"fun":20,"tension":5,"attachment":5}"""
        assertFalse(gate.canLaunch(acquaintedCard(initialJson)).allowed)
    }

    @Test fun established_relationship_launches() = runTest {
        coEvery { controlSettings.proactiveGate() } returns NewsControlSettings.ProactiveGate(enabled = true)
        assertTrue("质感已建立（≠INITIAL）→ 放行", gate.canLaunch(acquaintedCard()).allowed)
    }

    @Test fun intel_whitelist_bypasses_gate() = runTest {
        coEvery { controlSettings.proactiveGate() } returns NewsControlSettings.ProactiveGate(
            enabled = true, intelWhitelist = listOf("s1"),
        )
        assertTrue("情报型白名单无视闸门（业主 Q④·检查点=发射闸）", gate.canLaunch(strangerCard()).allowed)
    }

    @Test fun silent_persona_parameter_slot_blocks() = runTest {
        coEvery { controlSettings.proactiveGate() } returns NewsControlSettings.ProactiveGate(
            enabled = true, personaCoefficient = 0.0,
        )
        val decision = gate.canLaunch(acquaintedCard())
        assertFalse("人设沉默兜底（主动度系数≤0·参数位）", decision.allowed)
        assertTrue(decision.reason.contains("沉默"))
    }

    @Test fun stranger_block_off_allows() = runTest {
        coEvery { controlSettings.proactiveGate() } returns NewsControlSettings.ProactiveGate(enabled = true, strangerBlock = false)
        assertTrue("禁令一可单关（控制台口径）", gate.canLaunch(strangerCard()).allowed)
    }
}
