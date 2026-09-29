package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * [zCODE] P4·A5 手动关系登记表（正本五.2 登记制度落点——用户是登记员，此表是登记簿）。
 * 优先级高于三表派生（手动指定④旧识直接放行——对应"层级累加不降级"）。
 * UI 编辑入口归 P6 管理台；本批表可为空（无登记=走派生，行为与现在一致）。
 */
@Entity(tableName = "character_relations", indices = [Index(value = ["fromUuid", "toUuid"], unique = true)])
data class CharacterRelationEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val fromUuid: String,
    val toUuid: String,
    /** 层级（aware/acquainted/old_friend——对应②③④）。 */
    val level: String,
    /** 来源一句话（"A电话虫转报"等）。 */
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
