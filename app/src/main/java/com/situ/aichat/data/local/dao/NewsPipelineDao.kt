package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.situ.aichat.data.local.entity.NewsDeliveryEntity
import com.situ.aichat.data.local.entity.NewsEventEntity

/**
 * [zCODE] P3·摩根斯新闻管道 DAO。写入唯一入口 = NewsPipelineService。
 */
@Dao
interface NewsPipelineDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: NewsEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelivery(delivery: NewsDeliveryEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM news_events WHERE sourceRefUuid = :sourceRefUuid AND sourceRaw = :sourceRaw LIMIT 1)")
    suspend fun eventExistsBySource(sourceRaw: String, sourceRefUuid: String): Boolean

    @Query("SELECT * FROM news_events WHERE uuid = :uuid LIMIT 1")
    suspend fun getEvent(uuid: String): NewsEventEntity?

    @Query("SELECT * FROM news_events WHERE occurredAt >= :sinceMillis ORDER BY occurredAt DESC")
    suspend fun eventsSince(sinceMillis: Long): List<NewsEventEntity>

    @Query("SELECT * FROM news_deliveries WHERE targetCharacterUuid = :characterUuid AND deliveredAt <= :nowMillis ORDER BY deliveredAt DESC LIMIT :limit")
    suspend fun deliveredTo(characterUuid: String, nowMillis: Long, limit: Int): List<NewsDeliveryEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM news_deliveries WHERE newsEventUuid = :newsEventUuid AND targetCharacterUuid = :characterUuid)")
    suspend fun deliveryExists(newsEventUuid: String, characterUuid: String): Boolean

    // ── 角色删除级联（P3 追加） ──

    @Query("DELETE FROM news_events WHERE characterUuid = :characterUuid")
    suspend fun deleteEventsForCharacter(characterUuid: String)

    @Query("DELETE FROM news_deliveries WHERE targetCharacterUuid = :characterUuid")
    suspend fun deleteDeliveriesForCharacter(characterUuid: String)

    @Query("DELETE FROM news_deliveries WHERE newsEventUuid IN (SELECT uuid FROM news_events WHERE characterUuid = :characterUuid)")
    suspend fun deleteDeliveriesByEventCharacter(characterUuid: String)
}
