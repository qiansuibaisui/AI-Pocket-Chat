package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import kotlinx.coroutines.flow.Flow

/**
 * [zCODE] P1·第2项 锚点中央登记 DAO（评审点1）。写入口唯一持有者 = [com.situ.aichat.data.repository.StoryStateRepository]。
 * 插入一律 IGNORE：snapshots 靠唯一索引 (relatedMessageUUID, sourceRaw) 幂等（评审附加条件1），
 * ledger 靠唯一索引 (characterUuid, eventKey) 去重。
 */
@Dao
interface StoryStateDao {

    // ── 锚点快照 ──

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSnapshot(snapshot: StoryAnchorSnapshotEntity): Long

    /** 幂等预检（附加条件1）：同回合同通道已有行 → true（跳过写入）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM story_anchor_snapshots WHERE relatedMessageUUID = :relatedMessageUUID AND sourceRaw = :sourceRaw)")
    suspend fun snapshotExists(relatedMessageUUID: String, sourceRaw: String): Boolean

    /** 当前锚点 = 每角色最新一行。 */
    @Query("SELECT * FROM story_anchor_snapshots WHERE characterUuid = :characterUuid ORDER BY capturedAt DESC LIMIT 1")
    suspend fun latestSnapshotFor(characterUuid: String): StoryAnchorSnapshotEntity?

    /** 同上，Flow 版（P3 管理台/名片派生显示用）。 */
    @Query("SELECT * FROM story_anchor_snapshots WHERE characterUuid = :characterUuid ORDER BY capturedAt DESC LIMIT 1")
    fun observeLatestSnapshotFor(characterUuid: String): Flow<StoryAnchorSnapshotEntity?>

    /** 历史快照（P3 管理台时间线），限最近 [limit] 条。 */
    @Query("SELECT * FROM story_anchor_snapshots WHERE characterUuid = :characterUuid ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun snapshotHistoryFor(characterUuid: String, limit: Int): List<StoryAnchorSnapshotEntity>

    // ── 已完成事件账本 ──

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedger(entry: StoryEventLedgerEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM story_event_ledger WHERE characterUuid = :characterUuid AND eventKey = :eventKey)")
    suspend fun ledgerExists(characterUuid: String, eventKey: String): Boolean

    /** 窗口内已完成事件清单（读取处1「不得重新执行」注入的数据源）。 */
    @Query("SELECT * FROM story_event_ledger WHERE characterUuid = :characterUuid AND completedAt >= :sinceMillis ORDER BY completedAt DESC")
    suspend fun recentLedgerFor(characterUuid: String, sinceMillis: Long): List<StoryEventLedgerEntity>

    @Query("SELECT * FROM story_event_ledger WHERE characterUuid = :characterUuid ORDER BY completedAt DESC")
    suspend fun ledgerHistoryFor(characterUuid: String): List<StoryEventLedgerEntity>

    // ── 备份（13.6 全局段；整存整取） ──

    @Query("SELECT * FROM story_anchor_snapshots ORDER BY capturedAt ASC")
    suspend fun allSnapshots(): List<StoryAnchorSnapshotEntity>

    @Query("SELECT * FROM story_event_ledger ORDER BY completedAt ASC")
    suspend fun allLedger(): List<StoryEventLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceSnapshots(snapshots: List<StoryAnchorSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceLedger(entries: List<StoryEventLedgerEntity>)

    // ── [zCODE] P1·第3项 船团成员（世界锚点层） ──

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFleetMember(member: com.situ.aichat.data.local.entity.StoryFleetMemberEntity): Long

    @Query("DELETE FROM story_fleet_members WHERE fleetKey = :fleetKey AND characterUuid = :characterUuid")
    suspend fun removeFleetMember(fleetKey: String, characterUuid: String)

    /** 同团全部成员（广播扇出取材；无团 = 空）。 */
    @Query("SELECT * FROM story_fleet_members WHERE fleetKey = :fleetKey")
    suspend fun membersOfFleet(fleetKey: String): List<com.situ.aichat.data.local.entity.StoryFleetMemberEntity>

    /** 该卡所属团键（最新加入的一团；null = 未配团 fail-open）。 */
    @Query("SELECT fleetKey FROM story_fleet_members WHERE characterUuid = :characterUuid ORDER BY joinedAt DESC LIMIT 1")
    suspend fun fleetKeyOfCharacter(characterUuid: String): String?

    // ── [zCODE] 追加项A：角色删除级联清理 ──

    @Query("DELETE FROM story_anchor_snapshots WHERE characterUuid = :characterUuid")
    suspend fun deleteAnchorsForCharacter(characterUuid: String)

    @Query("DELETE FROM story_event_ledger WHERE characterUuid = :characterUuid")
    suspend fun deleteLedgerForCharacter(characterUuid: String)

    @Query("DELETE FROM story_fleet_members WHERE characterUuid = :characterUuid")
    suspend fun deleteFleetMembershipForCharacter(characterUuid: String)

    // ── 备份（第 21 段·整存整取） ──

    @Query("SELECT * FROM story_fleet_members ORDER BY joinedAt ASC")
    suspend fun allFleetMembers(): List<com.situ.aichat.data.local.entity.StoryFleetMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceFleetMembers(members: List<com.situ.aichat.data.local.entity.StoryFleetMemberEntity>)
}
