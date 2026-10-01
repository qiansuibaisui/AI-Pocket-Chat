package com.situ.aichat.morgans

import android.util.Log
import com.situ.aichat.data.local.dao.NewsPipelineDao
import com.situ.aichat.data.local.entity.NewsDeliveryEntity
import com.situ.aichat.data.local.entity.NewsEventEntity
import com.situ.aichat.data.local.entity.NewsSeverity
import com.situ.aichat.data.local.entity.StoryFleetMemberEntity
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.prompt.AnchorVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [zCODE] P3·摩根斯新闻管道（中继 1：数据结构 + 生成 + 落库）。
 *
 * 核心原则（规格书照录）：
 * - 摩根斯做功能不做卡：新闻条目发布者皮套，不建角色卡、不参与互动、不发朋友圈个人动态；
 * - 不在场角色只知报道版本：注入措辞强制"据报道/传闻/《世界经济学报》头版……"——不得以亲历口吻知晓细节；
 * - 知晓层级自动落库：新闻触达的角色，认知矩阵写入"经由摩根斯报道知晓"（来源标记 morgans 必带）。
 *
 * 传播延迟规则（中继 2 实现·本中继常量已定）：
 * - 同船在场者：不走新闻管道（已是亲历·落①层）——金样 7 防重叠；
 * - 同团不同船/同海域：短延迟（小时级 6h）；
 * - 跨海域/跨团：长延迟（天级 24h）；
 * - 无风带隔离：新闻鸟不到→延迟无穷（不注入）。
 *
 * 细节衰减（中继 2 实现·本中继级别枚举已定）：
 * - 随传播距离降级——具名引语→概述；精确数字→模糊量词；地点精确到岛→模糊到海域。
 *
 * 接口预留：[publish] 单一入口——P4 导演/P5 群聊经此发布（本批不写适配代码）。
 */
@Singleton
class NewsPipelineService @Inject constructor(
    private val dao: NewsPipelineDao,
    private val storyStateDao: StoryStateDao,
    private val characterDao: com.situ.aichat.data.local.dao.CharacterDao, // [zCODE] 中继3·项2：跨团扇出扩面
    private val controlSettings: NewsControlSettings, // [zCODE] 中继3·项1/3/4：C10 控制面（每源开关+概率/压级/知情名单）
) {

    // ── 传播延迟常量表（中继 1 定值·中继 2 接入计算） ──

    /** 同团/同海域短延迟（小时级）。 */
    val SHORT_DELAY_MS = 6L * 3600_000

    /** 跨海域/跨团长延迟（天级）。 */
    val LONG_DELAY_MS = 24L * 3600_000

    /** 无风带隔离（新闻鸟不到·不注入）。 */
    val CALM_BELT_BLOCKED = true

    /** [zCODE] 中继3·项3：零知晓压级哨值（手动配置 detailOverride=-1 → fanOut 全静默）。 */
    val EVENT_SILENCE = -1

    /**
     * 发布新闻事件（唯一入口·P4/P5 预留）：生成条目 → 对不在场角色扇出触达记录。
     *
     * 幂等：sourceRefUuid + sourceRaw 唯一索引——同一源事件重复发布零行。
     *
     * @param draft 新闻三要素（谁/在哪/干了什么）
     * @param participants 亲历者 uuid 列表（不走新闻管道——防①②重叠）
     * @return 新闻条目 uuid（已存在则返回 null=幂等跳过）
     */
    suspend fun publish(
        draft: NewsEventDraft,
        participants: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): String? = withContext(Dispatchers.IO) {
        // [zCODE] 中继3·项1（拍板③：每源独立开关+概率·判定权收编本口·默认=现行为）：
        // meeting_end 默认 开/1.0=每次必报；director_rules 默认 开/0.30=B9 原 30%（B9 侧自有掷点已移除，
        // 概率唯一执行点=此处）。开关关/概率不过 → 不落条目不扇出（幂等预检之后、插条之前，零残留）。
        val trigger = runCatching { controlSettings.triggerFor(draft.sourceRaw) }
            .getOrDefault(NewsControlSettings.SourceTrigger())
        if (!trigger.enabled) {
            Log.d(TAG, "新闻源已关（C10 控制面），跳过 source=${draft.sourceRaw}")
            return@withContext null
        }
        if (random.nextDouble() >= trigger.probability) {
            Log.d(TAG, "新闻源概率未过（C10 控制面 ${trigger.probability}），跳过 source=${draft.sourceRaw}")
            return@withContext null
        }
        // 幂等预检
        if (dao.eventExistsBySource(draft.sourceRaw, draft.sourceRefUuid)) {
            Log.d(TAG, "新闻条目幂等命中，跳过 sourceRef=${draft.sourceRefUuid.take(12)}")
            return@withContext null
        }
        // 生成条目
        val event = NewsEventEntity(
            characterUuid = draft.characterUuid,
            characterName = draft.characterName,
            locationKey = draft.locationKey,
            eventSummary = draft.eventSummary,
            severityRaw = draft.severity.raw,
            occurredAt = draft.occurredAt,
            sourceRaw = draft.sourceRaw,
            sourceRefUuid = draft.sourceRefUuid,
        )
        val rowId = dao.insertEvent(event)
        if (rowId == -1L) {
            Log.d(TAG, "新闻条目唯一索引兜底命中，跳过")
            return@withContext null
        }
        Log.i(TAG, "新闻条目发布: ${draft.characterName}在${draft.locationKey}·${draft.eventSummary.take(30)}")
        // 扇出触达（中继 1 全量立即触达·中继 2 接入延迟计算与无风带过滤）
        fanOut(event, participants, nowMillis)
        event.uuid
    }

    /**
     * 对不在场角色扇出触达（转述层落库）。
     * [zCODE] 中继3·项2（问二教训修复）：扩面=**全角色**按 [calculateDeliveryPlan] 距离档投递——
     * 目标团/目标锚点**实参真传**（原实现恒传 target==event fleet 致二三档生产不可达）；亲历者与事件主角排除。
     * [zCODE] 中继3·项3/4：单事件手动配置（压级+知情名单·同 blob）——
     * 优先级 **手动 > 闸门(severity floor) > 默认(距离档)**；detailOverride=-1 零知晓=全静默（覆盖 allowList）。
     */
    private suspend fun fanOut(event: NewsEventEntity, participants: List<String>, nowMillis: Long) {
        val override = runCatching { controlSettings.eventOverrideFor(event.sourceRefUuid) }.getOrNull()
        if (override?.detailOverride == EVENT_SILENCE) {
            Log.i(TAG, "新闻扇出：零知晓压级（C10 手动）event=${event.sourceRefUuid.take(12)}——全静默")
            return
        }
        val severity = com.situ.aichat.data.local.entity.NewsSeverity.fromRaw(event.severityRaw)
        val eventFleetKey = storyStateDao.fleetKeyOfCharacter(event.characterUuid)
        val allCharacters = runCatching { characterDao.getAll() }.getOrDefault(emptyList())
        var delivered = 0
        for (target in allCharacters) {
            if (target.uuid == event.characterUuid || target.uuid in participants) continue // 亲历链不走新闻
            // 项4 知情名单：手动指定后仅名单内获知（覆盖默认判定）
            if (override != null && override.allowList.isNotEmpty() && target.uuid !in override.allowList) continue
            val targetFleetKey = runCatching { storyStateDao.fleetKeyOfCharacter(target.uuid) }.getOrNull()
            val targetLocationKey = runCatching { storyStateDao.latestSnapshotFor(target.uuid) }
                .getOrNull()?.locationRaw?.takeIf { it.isNotBlank() }
            val plan = calculateDeliveryPlan(
                targetFleetKey = targetFleetKey,
                eventFleetKey = eventFleetKey,
                eventLocationKey = event.locationKey,
                targetLocationKey = targetLocationKey,
                severity = severity,
            ) ?: continue // 无风带/阻断
            // 项3 优先级落点：手动压级 > severity floor（floor 在 plan 内）> 默认距离档
            val detailLevel = override?.detailOverride?.takeIf { it >= 0 } ?: plan.detailLevel
            insertDelivery(event, target.uuid, nowMillis + plan.delayMs, detailLevel, nowMillis)
            delivered++
        }
        Log.d(TAG, "新闻扇出: event=${event.uuid.take(8)} 触达=${delivered} 人（全角色面·距离档）")
    }

    /**
     * [zCODE] 中继 2·延迟+衰减计算（纯函数·金样 2/3）：按目标与事件的舰队/海域关系三态判定。
     * [zCODE] 中继3·项3：severity 联动——MAJOR 事件 detailLevel 下限=1（最低概述级；NORMAL 照旧距离档）。
     *
     * @return null = 阻断（无风带）；非 null = DeliveryPlan(delayMs, detailLevel)
     */
    fun calculateDeliveryPlan(
        targetFleetKey: String?,
        eventFleetKey: String?,
        eventLocationKey: String,
        targetLocationKey: String? = null,
        severity: com.situ.aichat.data.local.entity.NewsSeverity =
            com.situ.aichat.data.local.entity.NewsSeverity.NORMAL,
    ): DeliveryPlan? {
        // 无风带：新闻鸟不到→不注入（事件地或目标地在无风带均阻断）
        val calmBeltDomains = listOf("九蛇岛", "亚马逊百合")
        val eventDomain = AnchorVocabulary.normalizeIslandTop(eventLocationKey)
        val targetDomain = targetLocationKey?.let { AnchorVocabulary.normalizeIslandTop(it) }
        if (eventDomain in calmBeltDomains || targetDomain in calmBeltDomains) return null

        val base = when {
            // 同团（不同船）→ 短延迟+详版
            targetFleetKey != null && targetFleetKey == eventFleetKey ->
                DeliveryPlan(SHORT_DELAY_MS, detailLevel = 2)
            // 同海域（岛名相同）→ 短延迟+概述
            targetDomain != null && targetDomain == eventDomain ->
                DeliveryPlan(SHORT_DELAY_MS, detailLevel = 1)
            // 跨海域/跨团 → 长延迟+模糊
            else -> DeliveryPlan(LONG_DELAY_MS, detailLevel = 0)
        }
        // 项3：MAJOR → 最低概述级（细节不外流，只许"听说出了大事"）
        return if (severity == com.situ.aichat.data.local.entity.NewsSeverity.MAJOR && base.detailLevel < 1) {
            base.copy(detailLevel = 1)
        } else base
    }

    /** 延迟+衰减计算结果。 */
    data class DeliveryPlan(val delayMs: Long, val detailLevel: Int)

    /**
     * [zCODE] 中继 2·消费侧注入（对话/朋友圈生成上下文共用）：取该角色已触达新闻、格式化为注入块。
     * 措辞含"据报道"+防补充红线（金样 5）；空→null（不注入）。
     */
    suspend fun newsInjectionFor(characterUuid: String, nowMillis: Long = System.currentTimeMillis()): String? =
        withContext(Dispatchers.IO) {
            val deliveries = dao.deliveredTo(characterUuid, nowMillis, limit = 3)
            if (deliveries.isEmpty()) return@withContext null
            val lines = deliveries.mapNotNull { d ->
                dao.getEvent(d.newsEventUuid)?.let { d.narrativeText.ifBlank { formatNarrative(it, d.detailLevel) } }
            }
            if (lines.isEmpty()) return@withContext null
            buildString {
                appendLine("【新闻·转述层】以下来自《世界经济学报》的报道（你不在场，只知道报道版本）：")
                lines.forEach { appendLine(it) }
                append("注意：你只知道上述报道内容，不得自行补充报道里没有的细节，不得以亲历口吻谈论这些事件。")
            }
        }

    /** 插入触达记录（幂等·unique index (newsEventUuid, targetCharacterUuid)）。 */
    private suspend fun insertDelivery(
        event: NewsEventEntity,
        targetUuid: String,
        deliverAt: Long,
        detailLevel: Int,
        nowMillis: Long,
    ) {
        if (dao.deliveryExists(event.uuid, targetUuid)) return
        val narrative = formatNarrative(event, detailLevel)
        dao.insertDelivery(
            NewsDeliveryEntity(
                newsEventUuid = event.uuid,
                targetCharacterUuid = targetUuid,
                knowledgeLayer = "reported",
                sourceTag = "morgans",
                deliveredAt = deliverAt,
                detailLevel = detailLevel,
                narrativeText = narrative,
            ),
        )
    }

    /**
     * 转述措辞格式化（衰减规则——中继 1 定基础版，中继 2 扩展距离衰减）。
     *
     * 防幻觉污染红线（注入块必含）：角色不得自行"补充"报道里没有的细节。
     */
    fun formatNarrative(event: NewsEventEntity, detailLevel: Int): String = buildString {
        append("（据报道——以下来自《世界经济学报》，")
        append("你不在场，只知道这个报道版本，")
        append("不得自行补充报道里没有的细节。）")
        append("「")
        when (detailLevel) {
            2 -> { // 详版
                append("${event.characterName}在${event.locationKey}${event.eventSummary}。")
            }
            1 -> { // 概述
                val domain = AnchorVocabulary.normalizeIslandTop(event.locationKey)
                append("${event.characterName}据报道在${domain ?: "某处"}${summarize(event.eventSummary)}。")
            }
            0 -> { // 模糊
                append("有传闻称${event.characterName}出了点动静（细节不明）。")
            }
        }
        append("」")
    }

    /** 事件摘要降级（概述版——截断到 30 字·去具体细节词）。 */
    private fun summarize(eventSummary: String): String =
        eventSummary.take(30).replace(Regex("[，。].*$"), "等")

    /** [zCODE] P3：角色删除级联（CharacterDeletionCleaner 调）。 */
    suspend fun deleteAllForCharacter(characterUuid: String) = withContext(Dispatchers.IO) {
        dao.deleteDeliveriesForCharacter(characterUuid)
        dao.deleteDeliveriesByEventCharacter(characterUuid)
        dao.deleteEventsForCharacter(characterUuid)
    }

    /** 角色删除辅助：事件主角的触达也需清。 */
    suspend fun deliveriesFor(characterUuid: String, nowMillis: Long, limit: Int = 5): List<NewsDeliveryEntity> =
        withContext(Dispatchers.IO) { dao.deliveredTo(characterUuid, nowMillis, limit) }

    companion object {
        const val TAG = "MorgansNews"
    }
}

/**
 * 新闻事件三要素草稿（publish 入参——P4/P5 触发源经此发布）。
 */
data class NewsEventDraft(
    val characterUuid: String,
    val characterName: String,
    val locationKey: String,
    val eventSummary: String,
    val severity: NewsSeverity = NewsSeverity.NORMAL,
    val occurredAt: Long = System.currentTimeMillis(),
    val sourceRaw: String = "meeting_end",
    val sourceRefUuid: String,
)
