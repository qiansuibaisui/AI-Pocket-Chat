package com.situ.aichat.schedule

import com.situ.aichat.data.local.dao.NewsPipelineDao
import com.situ.aichat.data.local.dao.StoryStateDao

/**
 * [zCODE] P4·A5/A6 关系闸门（演出为准·登记记账——正本核心口径）。
 *
 * 四级判定完全由现有三表派生（A5 勘察结论：无"跨团关系"列，用事实源替代）：
 * - ① 队友：story_fleet_members 同团
 * - ② 知晓：news_deliveries 报道触达（单向/双向由 delivery 方向判定）
 * - ③ 新识：story_event_ledger 已演出共同事件
 * - ④ 旧识：③ + 时间窗口（长期互动）
 * - 零级：无任何记录 → 互为陌生人
 *
 * A5 闸门：日程条目含跨团角色名 → 查层级 → ③以下拦截该条目（不杀批）。
 * A6 闸门：共同活动条目须有演出链（ledger/news 任一）→ 无来源拒该条目。
 * 核心口径：拦"未演出"不拦"未登记"——已演出未登记的关系放行。
 */
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelationGateService @Inject constructor(
    private val storyStateDao: StoryStateDao,
    private val newsDao: NewsPipelineDao,
    private val characterDao: com.situ.aichat.data.local.dao.CharacterDao? = null, // [zCODE] A组真收口：名→UUID（可选·测试兼容）
) {
    enum class Level { STRANGER, AWARE_ONEWAY, AWARE_MUTUAL, ACQUAINTED, OLD_FRIEND, FLEET_MATE }

    /**
     * [zCODE] A组真收口：管线级过滤——生成后按 relatedCharacterNames 过滤条目。
     * 名→UUID 解析 → 四源层级判定 → ③以下拦截该条（拦条不杀批）。
     * 无 DAO（测试场景）或无关联角色 = 放行（fail-open）。
     */
    suspend fun filterScheduleEvents(ownerUuid: String, events: List<com.situ.aichat.data.local.entity.ScheduleEventEntity>): List<com.situ.aichat.data.local.entity.ScheduleEventEntity> {
        val dao = characterDao ?: return events // 测试兼容：无 DAO 不过滤
        return events.filter { e ->
            val names = e.relatedCharacterNames ?: return@filter true
            val nameList = names.split("，", "、", ",", "「", "」").map { it.trim() }.filter { it.isNotEmpty() && it != "和" }
            if (nameList.isEmpty()) return@filter true
            nameList.all { name ->
                val target = runCatching { dao.getByName(name) }.getOrNull()
                if (target == null) return@all true // 名字查不到=可能非角色（用户名/地名）→ 放行
                runCatching { canAppearInSchedule(ownerUuid, target.uuid) }.getOrDefault(true) // 闸门异常不阻日程
            }
        }
    }

    /** 判定 a→b 的关系层级。优先级：手动登记 > ①同团 > ③已演出 > ②报道触达 > 零级。 */
    suspend fun levelBetween(aUuid: String, bUuid: String): Level {
        val now = System.currentTimeMillis()
        // [zCODE] 手动登记（第四源·最高优先——正本五.2 登记制度·用户指定层级直接生效）
        val manual = storyStateDao.getRelation(aUuid, bUuid) ?: storyStateDao.getRelation(bUuid, aUuid)
        if (manual != null) {
            return when (manual.level) {
                "old_friend" -> Level.OLD_FRIEND
                "acquainted" -> Level.ACQUAINTED
                "aware" -> Level.AWARE_MUTUAL
                else -> Level.STRANGER
            }
        }
        // ① 同团
        val fleetA = storyStateDao.fleetKeyOfCharacter(aUuid)
        val fleetB = storyStateDao.fleetKeyOfCharacter(bUuid)
        if (fleetA != null && fleetA == fleetB) return Level.FLEET_MATE
        // ③ 已演出（ledger 有共同事件——eventKey 含对方 uuid 或 description 含对方名）
        val ledgerA = storyStateDao.recentLedgerFor(aUuid, now - LEDGER_WINDOW_MS)
        if (ledgerA.any { it.eventKey.contains(bUuid) || it.description.contains(bUuid) }) {
            // ④ 旧识判定：事件距今超 7 天
            val oldest = ledgerA.filter { it.eventKey.contains(bUuid) }.minOfOrNull { it.completedAt } ?: 0L
            return if (now - oldest > OLD_FRIEND_WINDOW_MS) Level.OLD_FRIEND else Level.ACQUAINTED
        }
        // ② 知晓（news 触达）
        val newsA = newsDao.deliveredTo(aUuid, now, 10)
        val knowsB = newsA.any { d -> d.targetCharacterUuid == aUuid && newsDao.getEvent(d.newsEventUuid)?.characterUuid == bUuid }
        val newsB = newsDao.deliveredTo(bUuid, now, 10)
        val knowsA = newsB.any { d -> d.targetCharacterUuid == bUuid && newsDao.getEvent(d.newsEventUuid)?.characterUuid == aUuid }
        return when {
            knowsB && knowsA -> Level.AWARE_MUTUAL
            knowsB || knowsA -> Level.AWARE_ONEWAY
            else -> Level.STRANGER
        }
    }

    /** A5 闸门：条目中的跨团角色是否有权出现在日程（③ 新识以上才可共同活动条目）。 */
    suspend fun canAppearInSchedule(aUuid: String, bUuid: String): Boolean {
        val level = levelBetween(aUuid, bUuid)
        return level >= Level.ACQUAINTED // ③以上放行；②/零级禁现
    }

    /** A6 闸门：共同活动是否有演出链（ledger 或 news 任一即可）。与 A5 同面：拦条不杀批。 */
    suspend fun hasProvenanceForJointActivity(aUuid: String, bUuid: String): Boolean {
        return canAppearInSchedule(aUuid, bUuid) // 演出链即层级≥③ 的判定基础
    }

    companion object {
        val LEDGER_WINDOW_MS = 30L * 24 * 3600_000 // 30 天演出窗口
        val OLD_FRIEND_WINDOW_MS = 7L * 24 * 3600_000 // 7 天即旧识（长期互动）
    }
}
