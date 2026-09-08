package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 未来约定见面读写。落库 + 状态机改写 + 确认卡气泡 / 倒数小条实时观察 + 删角色·删会话清理 + 备份导出/恢复。
 *
 * 状态机改写走纯函数 copy + [update]（不可变 Room 行，与 RedPacketDao 一致）。
 * SQL 里的 'proposed' / 'confirmed' 字面量 = [com.situ.aichat.data.model.MeetingStatus] 对应 raw，
 * 由 MeetingAppointmentTypesTest 锁定（重命名 raw 会断测试，而非静默使「活跃约定」查询失配）。
 */
@Dao
interface MeetingAppointmentDao {

    /** 识别入库 / 手动发起时插入。 */
    @Insert
    suspend fun insert(appointment: MeetingAppointmentEntity)

    /** 状态流转（confirm/honor/miss/cancel/改期）改写整行，纯函数 copy 后 @Update。 */
    @Update
    suspend fun update(appointment: MeetingAppointmentEntity)

    /** 按 uuid 取（赴约 / 改期 / 取消入口 fetch + 状态校验；找不到 → null）。 */
    @Query("SELECT * FROM meeting_appointments WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): MeetingAppointmentEntity?

    /** 全表约定条数（性能采集规模数 `meetingAppointments`·图纸 §3.2·BG-4 的「>100 按 MED 排」判定源）。 */
    @Query("SELECT COUNT(*) FROM meeting_appointments")
    suspend fun countAll(): Int

    /** 按 uuid 响应式观察（确认卡气泡 / 详情弹窗实时刷新状态）。 */
    @Query("SELECT * FROM meeting_appointments WHERE uuid = :uuid LIMIT 1")
    fun observeByUuid(uuid: String): Flow<MeetingAppointmentEntity?>

    // ── 活跃约定查询（proposed/confirmed·按见面时间升序）：查重 / 倒数小条 / prompt 注入 / 爽约检测 ──

    /** 某角色进行中的约定。供查重 + 倒数小条（资料页·角色级）。 */
    @Query(
        "SELECT * FROM meeting_appointments WHERE characterUuid = :characterUuid " +
            "AND status IN ('proposed', 'confirmed') ORDER BY scheduledAt ASC",
    )
    suspend fun activeForCharacter(characterUuid: String): List<MeetingAppointmentEntity>

    /** 某角色进行中约定的响应式观察（资料页·角色级倒数小条实时刷新·Phase 12）。 */
    @Query(
        "SELECT * FROM meeting_appointments WHERE characterUuid = :characterUuid " +
            "AND status IN ('proposed', 'confirmed') ORDER BY scheduledAt ASC",
    )
    fun observeActiveForCharacter(characterUuid: String): Flow<List<MeetingAppointmentEntity>>

    /** 某会话进行中的约定。供 prompt 注入 / 倒数小条（聊天页·会话级）/ 爽约检测（多会话错位坑：按会话过滤）。 */
    @Query(
        "SELECT * FROM meeting_appointments WHERE conversationUuid = :conversationUuid " +
            "AND status IN ('proposed', 'confirmed') ORDER BY scheduledAt ASC",
    )
    suspend fun activeForConversation(conversationUuid: String): List<MeetingAppointmentEntity>

    /** 某会话进行中约定的响应式观察（倒数小条实时刷新）。 */
    @Query(
        "SELECT * FROM meeting_appointments WHERE conversationUuid = :conversationUuid " +
            "AND status IN ('proposed', 'confirmed') ORDER BY scheduledAt ASC",
    )
    fun observeActiveForConversation(conversationUuid: String): Flow<List<MeetingAppointmentEntity>>

    // ── 删角色清理（无 FK，手动删；先取 uuid 去撤到点通知，再删记录，防孤儿通知） ──
    @Query("SELECT uuid FROM meeting_appointments WHERE characterUuid = :characterUuid")
    suspend fun uuidsForCharacter(characterUuid: String): List<String>

    @Query("DELETE FROM meeting_appointments WHERE characterUuid = :characterUuid")
    suspend fun deleteForCharacter(characterUuid: String)

    // ── 删会话清理（同上，按会话） ──
    @Query("SELECT uuid FROM meeting_appointments WHERE conversationUuid IN (:conversationUuids)")
    suspend fun uuidsForConversations(conversationUuids: List<String>): List<String>

    @Query("DELETE FROM meeting_appointments WHERE conversationUuid IN (:conversationUuids)")
    suspend fun deleteForConversations(conversationUuids: List<String>)

    // ── 备份（13.6 全局段；含全部状态，confirmed 恢复后重新参与到点重排 / 扫描） ──
    /** 全部约定（createdAt 升序），供备份导出。 */
    @Query("SELECT * FROM meeting_appointments ORDER BY createdAt ASC")
    suspend fun getAllAppointments(): List<MeetingAppointmentEntity>

    /** 备份恢复用：按 uuid 覆盖式插入（再导入幂等）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(appointment: MeetingAppointmentEntity)

    /**
     * [zCODE] P1·矛盾守卫取材：同角色、排除指定 uuid、createdAt 在窗口内的待确认（proposed）兄弟约定。
     * 供「保留最新作废旧条目」守卫逐条置 superseded（终态、不可再流转）。
     */
    @Query(
        "SELECT * FROM meeting_appointments WHERE characterUuid = :characterUuid " +
            "AND uuid != :excludeUuid AND status = 'proposed' AND createdAt >= :sinceMillis",
    )
    suspend fun pendingSiblingsForCharacter(characterUuid: String, excludeUuid: String, sinceMillis: Long): List<MeetingAppointmentEntity>
}
