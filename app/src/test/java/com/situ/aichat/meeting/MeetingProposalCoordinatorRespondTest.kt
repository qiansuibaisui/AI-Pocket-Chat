package com.situ.aichat.meeting

import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * [zCODE] P1·确认卡统一响应入口（respondFromCard）单测：幂等 + 矛盾守卫（需求文档 v3 第 1 项实锤病灶）。
 *
 * 锁定三条：
 * 1. 幂等——仅 proposed 可响应；重复点击/终态 no-op，不双写；
 * 2. 卡回执与真理源同源落盘（accepted/declined）；
 * 3. 矛盾守卫——同角色 24h 窗口内其余待确认兄弟约定被作废（superseded）且对应卡收回执，
 *    「已接受+已婉拒」矛盾状态不得并存落盘。
 */
class MeetingProposalCoordinatorRespondTest {

    private val store = mockk<MeetingAppointmentStore>()
    private val messageRepo = mockk<MessageRepository>(relaxed = true)
    private val coordinator = MeetingProposalCoordinator(store, messageRepo)

    private fun appt(uuid: String, status: String, characterUuid: String = "char-1", createdAt: Long = 1_000L) =
        MeetingAppointmentEntity(uuid = uuid, characterUuid = characterUuid, conversationUuid = "conv-1", status = status, createdAt = createdAt)

    private fun cardMessage(appointmentUuid: String, responded: String? = null) = MessageEntity(
        messageUUID = UUID.randomUUID().toString(),
        conversationUuid = "conv-1",
        roleRaw = "assistant",
        content = FutureMeetingProposalJson.encode(FutureMeetingProposalData(appointmentUuid = appointmentUuid, responded = responded)),
        timestamp = 1_000L,
        messageKindRaw = MessageKind.FUTURE_MEETING_PROPOSAL_CARD.raw,
    )

    @Test fun non_proposed_is_noop_idempotent() = runTest {
        coEvery { store.get("a1") } returns appt("a1", MeetingStatus.CONFIRMED.raw)
        assertNull(coordinator.respondFromCard("a1", accepted = false))
        coVerify(exactly = 0) { store.cancel(any(), any()) }
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test fun accept_marks_card_receipt_and_supersedes_siblings() = runTest {
        val target = appt("a1", MeetingStatus.PROPOSED.raw)
        val sibling = appt("a2", MeetingStatus.PROPOSED.raw)
        coEvery { store.get("a1") } returns target
        coEvery { store.confirm("a1", any()) } returns target.copy(status = MeetingStatus.CONFIRMED.raw)
        coEvery { store.supersedeSiblingProposals("char-1", "a1", any(), any()) } returns listOf(sibling)
        coEvery { messageRepo.messagesByKind("conv-1", MessageKind.FUTURE_MEETING_PROPOSAL_CARD.raw) } returns listOf(
            cardMessage("a1"),
            cardMessage("a2"),
        )

        val updated = coordinator.respondFromCard("a1", accepted = true)

        assertEquals(MeetingStatus.CONFIRMED.raw, updated?.status)
        val receipts = mutableListOf<MessageEntity>()
        coVerify(atLeast = 2) { messageRepo.upsert(capture(receipts)) }
        val receiptByAppt = receipts.mapNotNull { FutureMeetingProposalJson.parse(it.content) }.associateBy { it.appointmentUuid }
        assertEquals(FutureMeetingProposalData.RESPONDED_ACCEPTED, receiptByAppt["a1"]?.responded)
        // 兄弟卡收 superseded 回执（按钮收起）——「已接受+已婉拒」矛盾态防并存
        assertEquals(FutureMeetingProposalData.RESPONDED_SUPERSEDED, receiptByAppt["a2"]?.responded)
    }

    @Test fun double_response_is_noop() = runTest {
        // 第一次响应后状态已流转：第二次（重放/重复点击）store.get 回非 proposed → no-op
        coEvery { store.get("a1") } returns appt("a1", MeetingStatus.CONFIRMED.raw)
        assertNull(coordinator.respondFromCard("a1", accepted = true))
        coVerify(exactly = 0) { store.confirm(any(), any()) }
    }

    @Test fun responded_cards_are_not_rewritten() = runTest {
        // 已收回执的卡（responded 非空）不再改写——回执幂等
        val target = appt("a1", MeetingStatus.PROPOSED.raw)
        coEvery { store.get("a1") } returns target
        coEvery { store.cancel("a1", any()) } returns target.copy(status = MeetingStatus.CANCELLED.raw)
        coEvery { store.supersedeSiblingProposals(any(), any(), any(), any()) } returns emptyList()
        coEvery { messageRepo.messagesByKind("conv-1", MessageKind.FUTURE_MEETING_PROPOSAL_CARD.raw) } returns listOf(
            cardMessage("a1", responded = FutureMeetingProposalData.RESPONDED_DECLINED), // 早已回执
        )

        coordinator.respondFromCard("a1", accepted = false)

        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test fun decline_on_proposed_cancels_and_marks_receipt() = runTest {
        val target = appt("a1", MeetingStatus.PROPOSED.raw)
        coEvery { store.get("a1") } returns target
        coEvery { store.cancel("a1", any()) } returns target.copy(status = MeetingStatus.CANCELLED.raw)
        coEvery { store.supersedeSiblingProposals(any(), any(), any(), any()) } returns emptyList()
        coEvery { messageRepo.messagesByKind("conv-1", MessageKind.FUTURE_MEETING_PROPOSAL_CARD.raw) } returns listOf(cardMessage("a1"))

        val updated = coordinator.respondFromCard("a1", accepted = false)

        assertEquals(MeetingStatus.CANCELLED.raw, updated?.status)
        val receipts = mutableListOf<MessageEntity>()
        coVerify(atLeast = 1) { messageRepo.upsert(capture(receipts)) }
        assertTrue(
            receipts.any { FutureMeetingProposalJson.parse(it.content)?.responded == FutureMeetingProposalData.RESPONDED_DECLINED },
        )
    }
}
