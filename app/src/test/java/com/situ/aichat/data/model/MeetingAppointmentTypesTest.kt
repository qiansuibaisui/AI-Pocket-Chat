package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「未来约定见面」枚举纯函数单测。锁定各枚举 raw 字面（= 数据库列 / 备份 JSON 的稳定契约，重命名断此测试而非
 * 静默使历史数据 / 后续 status 查询失配）+ fromRaw 未知回退 + [MeetingStatus.isActive] 语义。
 */
class MeetingAppointmentTypesTest {

    // ── MeetingStatus ──

    @Test fun status_raw_values_locked() {
        assertEquals("proposed", MeetingStatus.PROPOSED.raw)
        assertEquals("confirmed", MeetingStatus.CONFIRMED.raw)
        assertEquals("honored", MeetingStatus.HONORED.raw)
        assertEquals("missed", MeetingStatus.MISSED.raw)
        assertEquals("cancelled", MeetingStatus.CANCELLED.raw)
        assertEquals("superseded", MeetingStatus.SUPERSEDED.raw) // [zCODE] P1 矛盾守卫终态
    }

    @Test fun status_is_active_only_proposed_and_confirmed() {
        assertTrue(MeetingStatus.PROPOSED.isActive)
        assertTrue(MeetingStatus.CONFIRMED.isActive)
        assertFalse(MeetingStatus.HONORED.isActive)
        assertFalse(MeetingStatus.MISSED.isActive)
        assertFalse(MeetingStatus.CANCELLED.isActive)
        assertFalse(MeetingStatus.SUPERSEDED.isActive) // [zCODE] P1：终态，不参与任何流转/查重/排程
    }

    @Test fun status_from_raw_unknown_falls_back_proposed() {
        assertEquals(MeetingStatus.CONFIRMED, MeetingStatus.fromRaw("confirmed"))
        assertEquals(MeetingStatus.MISSED, MeetingStatus.fromRaw("missed"))
        assertEquals(MeetingStatus.SUPERSEDED, MeetingStatus.fromRaw("superseded")) // [zCODE] P1：新值不落 PROPOSED 兜底
        assertEquals(MeetingStatus.PROPOSED, MeetingStatus.fromRaw("garbage"))
    }

    // ── MeetingProposedBy ──

    @Test fun proposed_by_raw_and_fallback() {
        assertEquals("character", MeetingProposedBy.CHARACTER.raw)
        assertEquals("user", MeetingProposedBy.USER.raw)
        assertEquals(MeetingProposedBy.USER, MeetingProposedBy.fromRaw("user"))
        assertEquals(MeetingProposedBy.CHARACTER, MeetingProposedBy.fromRaw("garbage"))
    }

    // ── MeetingSource ──

    @Test fun source_raw_and_fallback() {
        assertEquals("extraction", MeetingSource.EXTRACTION.raw)
        assertEquals("tool", MeetingSource.TOOL.raw)
        assertEquals("fallback", MeetingSource.FALLBACK.raw)
        assertEquals("manual", MeetingSource.MANUAL.raw)
        assertEquals(MeetingSource.MANUAL, MeetingSource.fromRaw("manual"))
        assertEquals(MeetingSource.EXTRACTION, MeetingSource.fromRaw("garbage"))
    }

    // ── MeetingTimeGranularity ──

    @Test fun granularity_raw_and_fallback() {
        assertEquals("exact", MeetingTimeGranularity.EXACT.raw)
        assertEquals("dayOnly", MeetingTimeGranularity.DAY_ONLY.raw)
        assertEquals("vague", MeetingTimeGranularity.VAGUE.raw)
        assertEquals(MeetingTimeGranularity.DAY_ONLY, MeetingTimeGranularity.fromRaw("dayOnly"))
        assertEquals(MeetingTimeGranularity.EXACT, MeetingTimeGranularity.fromRaw("garbage"))
    }
}
