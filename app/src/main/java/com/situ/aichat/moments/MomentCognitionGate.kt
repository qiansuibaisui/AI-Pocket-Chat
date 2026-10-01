package com.situ.aichat.moments

import com.situ.aichat.schedule.RelationGateService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [zCODE] 中继3·项5(v2)·禁令二：**单向知晓不得朋友圈互动**（治 B6/F2 实证病灶）。
 *
 * 认知边界三.②原文口径：单向知晓"双方均不可朋友圈互动"；双向知晓才可低频点赞/简短评论。
 * 现状病灶（对单问B勘察实证）：MomentInteractionService 选角仅按质感/兴趣/活跃评分
 * （MomentInteractionService.kt:134-139·relationship=character.relationshipQuality 为**角色×用户**质感），
 * **零认知层级检查**——角色×角色的人际知晓度从不参与选角 → 单向知晓甚至陌生人角色可点赞/评论角色动态。
 *
 * 拦截点=选角入筛前（autoInteractWithPost 的 availableCandidates 之后·睡眠队列之前——被拦者不入待互动队列）。
 * 用户动态（post.characterUuid=null）不拦：角色×用户质感评分已既有管辖。
 * 架构区分：A6 管互动侧=本闸；RelationGateService 本体零改（仅消费其层级判定）。
 */
@Singleton
class MomentCognitionGate @Inject constructor(
    private val relationGate: RelationGateService,
) {

    /**
     * 候选角色可否对该动态互动。作者=用户（null）→ 放行（质感评分管辖）；
     * 作者=角色 → 须层级 ≥ **双向知晓**（AWARE_MUTUAL）——单向知晓/陌生人禁互动（认知边界三.②）。
     * 闸门异常 fail-open（不阻既有互动链·与 A5 同口径）。
     */
    suspend fun interactionAllowed(candidateUuid: String, postAuthorUuid: String?): Boolean {
        if (postAuthorUuid.isNullOrBlank()) return true
        return runCatching {
            relationGate.levelBetween(candidateUuid, postAuthorUuid) >= RelationGateService.Level.AWARE_MUTUAL
        }.getOrDefault(true)
    }
}
