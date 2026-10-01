package com.situ.aichat.morgans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zCODE] 中继3·项1/3/4 金样：控制面默认表（=现行为·拍板③）+手动配置 blob 编解码对称（压级与知情名单同入口）。
 */
class NewsControlSettingsBlobsTest {

    @Test fun default_triggers_match_current_behavior() {
        // 拍板③：初始默认=现行为——见面必报（1.0）/B9 30%（伴生级默认表·免实例可测）
        val d = NewsControlSettings.defaultTriggers
        assertEquals(NewsControlSettings.SourceTrigger(true, 1.0), d["meeting_end"])
        assertEquals(NewsControlSettings.SourceTrigger(true, 0.30), d["director_rules"])
    }

    @Test fun trigger_blob_roundtrip() {
        val map = mapOf("meeting_end" to NewsControlSettings.SourceTrigger(enabled = false, probability = 0.5))
        val decoded = NewsControlSettings.Blobs.decodeTriggers(NewsControlSettings.Blobs.encodeTriggers(map))
        assertEquals(map, decoded)
        assertTrue("垃圾串→空表（fail-soft）", NewsControlSettings.Blobs.decodeTriggers("not-json").isEmpty())
    }

    @Test fun event_override_blob_roundtrip_silence_and_allowlist() {
        // 压级（-1 零知晓/0 模糊）与知情名单同一 blob 两类字段（拍板①：一个配置入口）
        val map = mapOf(
            "session-9" to NewsControlSettings.EventOverride(detailOverride = -1),
            "dir-r:贝拉-艾斯:20270115" to NewsControlSettings.EventOverride(allowList = listOf("u1", "u2")),
        )
        val decoded = NewsControlSettings.Blobs.decodeOverrides(NewsControlSettings.Blobs.encodeOverrides(map))
        assertEquals(-1, decoded["session-9"]?.detailOverride) // 零知晓哨值对称
        assertEquals(listOf("u1", "u2"), decoded["dir-r:贝拉-艾斯:20270115"]?.allowList)
        assertTrue(NewsControlSettings.Blobs.decodeOverrides("garbage").isEmpty())
    }

    @Test fun director_tuning_defaults_equal_constants() {
        val t = NewsControlSettings.DirectorTuning()
        assertEquals(com.situ.aichat.director.DirectorRulesConfig.TRIGGER_PROBABILITY, t.triggerProbability, 0.0)
        assertEquals(com.situ.aichat.director.DirectorRulesConfig.DAILY_CAP, t.dailyCap)
        assertEquals(com.situ.aichat.director.DirectorRulesConfig.PAIR_COOLDOWN_MS, t.pairCooldownMs)
        assertEquals(com.situ.aichat.director.DirectorRulesConfig.PROXY_MAX_AGE_MS, t.proxyMaxAgeMs)
    }
}
