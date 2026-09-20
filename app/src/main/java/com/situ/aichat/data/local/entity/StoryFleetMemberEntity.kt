package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * [zCODE] P1·第3项 世界锚点层：船团成员映射表（评审点3·裁决1 方案 b）。
 *
 * 船团是**关系不是角色属性**（一船团跨多城航行、与 world 城市系统解耦）：建团/退团为手动配置
 * （角色卡管理·预填 joinedWorld 分组作默认值——运行期配置数据，不阻塞开发）；成员查询/广播/防环全自动。
 * 未配团卡 = 单卡行为零副作用（fail-open 延续）。
 *
 * [conversationUuid] = 该卡的主会话（world_sync ⚓ 通知投递目标；空 = 仅写行不通知）。
 */
@Entity(
    tableName = "story_fleet_members",
    indices = [
        Index(value = ["characterUuid"]),
        Index(value = ["fleetKey", "characterUuid"], unique = true),
    ],
)
data class StoryFleetMemberEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    /** 船团键（自由字符串标识，如 "whitebeard"）。同团共享基线锚点。 */
    val fleetKey: String,
    val characterUuid: String,
    val conversationUuid: String = "",
    val joinedAt: Long = System.currentTimeMillis(),
)
