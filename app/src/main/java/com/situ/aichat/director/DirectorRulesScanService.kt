package com.situ.aichat.director

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryAnchorSnapshotEntity
import com.situ.aichat.data.repository.StoryStateRepository
import com.situ.aichat.morgans.NewsEventDraft
import com.situ.aichat.morgans.NewsPipelineService
import com.situ.aichat.prompt.AnchorBlockParser
import com.situ.aichat.prompt.AnchorVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * [zCODE] B9 规则档=远方轻量事件引擎（中继2·终审拍板 2026-10-01）。
 *
 * 只适用于 {{user}} 不在场的远方场景：扫描**同位置**（拍板C 双层口径：fleetLayer 海域+MotionState 一致，
 * 双层锚点缺失回退岛名顶层+词表状态）的**跨团**角色对，四闸（概率/日上限/单对冷却/唯一索引幂等）后触发
 * 一两句轻量相遇事件，经 [StoryStateRepository.applyDirectorEvent] 通道②记账（双方 ledger 行=亲历③新识）。
 *
 * 纪律红线（结构性保证）：
 * - **{{user}} 侧零写入**——本服务不注入任何消息仓储，获知唯一通道=30% 择机 [NewsPipelineService.publish]
 *   （participants=双方排除亲历者，延迟三态扇出=不在场角色的转报通道）；
 * - **不走 broadcast/world_sync ⚓**——两人相遇≠全船移动（佩罗娜教训同源；applyDirectorEvent 本身不广播）；
 * - **用户在场判定 fail-closed**——位置代理失效（无最近线下见面/超 24h）则整轮放弃，不赌"远方"。
 */
@Singleton
class DirectorRulesScanService @Inject constructor(
    private val storyStateDao: StoryStateDao,
    private val characterDao: CharacterDao,
    private val storyRepo: StoryStateRepository,
    private val newsPipeline: NewsPipelineService,
    private val controlSettings: com.situ.aichat.morgans.NewsControlSettings, // [zCODE] 中继3·项1：参数位迁 DataStore（默认=常量现值）
) {

    /** 同位置坐标（双层口径的归一产物）。 */
    data class CoLocation(val key: String, val motion: AnchorVocabulary.MotionState, val display: String)

    /**
     * 锚点 → 同位置坐标。双层口径：fleetLayer 海域在场 → `sea:{海域}`+舰级 MotionState；
     * 回退：岛名顶层 `island:{岛}` + 词表状态。不可归一（无海域无岛名）或状态 UNKNOWN/INDOORS/IN_TRANSIT → null（不参与配对）。
     */
    fun coLocationOf(anchor: StoryAnchorSnapshotEntity): CoLocation? {
        val fleet = AnchorBlockParser.FleetLayerCodec.decode(anchor.fleetLayerJson)
        if (fleet != null && fleet.seaArea.isNotBlank()) {
            val motion = AnchorVocabulary.fleetMotionFromText(fleet.motionText)
                ?: AnchorVocabulary.MotionState.fromRaw(anchor.motionStateRaw)
            if (motion == AnchorVocabulary.MotionState.UNKNOWN) return null
            return CoLocation("sea:${fleet.seaArea}", motion, fleet.seaArea)
        }
        val island = AnchorVocabulary.normalizeIslandTop(anchor.locationRaw) ?: return null
        val motion = AnchorVocabulary.normalize(anchor.locationRaw).state
        if (motion == AnchorVocabulary.MotionState.UNKNOWN) return null
        return CoLocation("island:$island", motion, island)
    }

    /** 同位置判定：坐标键一致 + MotionState 一致 + 状态可配对（UNKNOWN 已在 coLocationOf 拦；INDOORS/IN_TRANSIT 不可断言同地）。 */
    fun sameLocation(a: StoryAnchorSnapshotEntity, b: StoryAnchorSnapshotEntity): Boolean {
        val ca = coLocationOf(a) ?: return false
        val cb = coLocationOf(b) ?: return false
        if (ca.motion != cb.motion) return false
        if (ca.motion == AnchorVocabulary.MotionState.INDOORS || ca.motion == AnchorVocabulary.MotionState.IN_TRANSIT) return false
        return ca.key == cb.key
    }

    /** pairKey：两角色名排序拼接（ledger 可读性；同名概率可忽略，eventKey 幂等口径一致即可）。 */
    fun pairKeyOf(a: CharacterEntity, b: CharacterEntity): String =
        listOf(a.name, b.name).sorted().joinToString("-")

    /** B9 eventKey：`dir-r:{pairKey}:{yyyyMMdd}`（拍板A——唯一索引即"每对每日≤1"硬闸）。 */
    fun eventKeyOf(pairKey: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "dir-r:$pairKey:" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(Instant.ofEpochMilli(nowMillis))

    /**
     * 用户位置代理（拍板C 修正版）：userFleetKeyOverride 设置非空 → 该团成员最新锚点；
     * 否则最近一次 **OFFLINE_MEETING** 记账行（DAO source 过滤=只认线下见面，电话虫等异地通话结构性不计入）
     * → 该角色当前锚点。代理锚点须 ≤proxyMaxAgeMs（DataStore 调参·默认常量），否则 null（fail-closed）。
     */
    suspend fun userProxyAnchor(nowMillis: Long): StoryAnchorSnapshotEntity? {
        val tuning = runCatching { controlSettings.directorTuning() }
            .getOrDefault(com.situ.aichat.morgans.NewsControlSettings.DirectorTuning())
        val overrideFleet = runCatching { controlSettings.userFleetKeyOverride() }.getOrDefault("")
        if (overrideFleet.isNotBlank()) {
            val member = storyStateDao.membersOfFleet(overrideFleet).firstOrNull() ?: return null
            return storyStateDao.latestSnapshotFor(member.characterUuid)
                ?.takeIf { nowMillis - it.effectiveAt <= tuning.proxyMaxAgeMs }
        }
        val meeting = storyStateDao.latestOfflineMeetingLedger() ?: return null
        if (nowMillis - meeting.completedAt > tuning.proxyMaxAgeMs) return null
        return storyStateDao.latestSnapshotFor(meeting.characterUuid)
            ?.takeIf { nowMillis - it.effectiveAt <= tuning.proxyMaxAgeMs }
    }

    /**
     * 一轮扫描（Worker 每 [DirectorRulesConfig.SCAN_INTERVAL_MS] 调一次）。返回触发的相遇事件数。
     * [random] 注入式（金样确定性：种子控制概率闸与模板挑选）。
     * 判定参数（触发概率/日上限/单对冷却）经 [com.situ.aichat.morgans.NewsControlSettings] 读 DataStore
     * （C10 控制面·默认=DirectorRulesConfig 常量现值）；**择机报道概率已收编 publish 单口**（B9 侧掷点移除）。
     */
    suspend fun scanOnce(
        nowMillis: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int = withContext(Dispatchers.IO) {
        val tuning = runCatching { controlSettings.directorTuning() }
            .getOrDefault(com.situ.aichat.morgans.NewsControlSettings.DirectorTuning())
        val proxy = userProxyAnchor(nowMillis)
        if (proxy == null) {
            Log.i(TAG, "B9 扫描放弃：用户位置代理失效（无近期线下见面/超24h）——fail-closed 不赌远方")
            return@withContext 0
        }
        val chars = runCatching { characterDao.getAll() }.getOrDefault(emptyList())
        // 合格单体：fresh 锚点 + 可归一同位置坐标 + 不与代理同位置（{{user}} 在场即非远方）
        val eligible = chars.mapNotNull { c ->
            val anchor = runCatching { storyStateDao.latestSnapshotFor(c.uuid) }.getOrNull() ?: return@mapNotNull null
            val fresh = AnchorVocabulary.freshnessOf(anchor.effectiveAt, nowMillis) == AnchorVocabulary.FreshnessLevel.FRESH
            if (!fresh) return@mapNotNull null
            coLocationOf(anchor)?.let { c to anchor }
        }.filterNot { (_, anchor) -> sameLocation(anchor, proxy) }

        // 跨团配对 + 同位置
        val fleets = eligible.associate { (c, _) -> c.uuid to storyStateDao.fleetKeyOfCharacter(c.uuid) }
        val candidates = buildList {
            for (i in eligible.indices) for (j in i + 1 until eligible.size) {
                val (ca, aa) = eligible[i]
                val (cb, ab) = eligible[j]
                val fa = fleets[ca.uuid]
                val fb = fleets[cb.uuid]
                if (fa != null && fa == fb) continue // 同团=日常同船生活，不是"相遇事件"
                if (!sameLocation(aa, ab)) continue
                add(Triple(ca, cb, coLocationOf(aa)!!.display))
            }
        }
        if (candidates.isEmpty()) return@withContext 0

        val dayStart = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        var fired = 0
        for ((a, b, loc) in candidates) {
            if (fired >= tuning.dailyCap) break
            if (runCatching { storyStateDao.directorRulesEventCountSince(dayStart) }.getOrDefault(0) >= tuning.dailyCap) break
            val pairKey = pairKeyOf(a, b)
            val cooldownHit = runCatching {
                storyStateDao.ledgerCountByKeyPrefixSince("dir-r:$pairKey:%", nowMillis - tuning.pairCooldownMs)
            }.getOrDefault(0) > 0
            if (cooldownHit) continue
            if (random.nextDouble() >= tuning.triggerProbability) continue
            val eventKey = eventKeyOf(pairKey, nowMillis, zone)
            val description = encounterSentence(random, a.name, b.name, loc)
            runCatching {
                storyRepo.applyDirectorEvent(
                    characterUuids = listOf(a.uuid, b.uuid),
                    conversationUuids = emptyMap(),
                    eventName = "远方相遇",
                    locationRaw = loc,
                    eventKey = eventKey,
                    description = description,
                    nowMillis = nowMillis,
                )
            }.onFailure { Log.w(TAG, "B9 相遇落账失败（跳过该对）pair=$pairKey: ${it.message}") ; continue }
            fired++
            Log.i(TAG, "B9 远方相遇触发: $description（key=$eventKey）")
            // 择机报道：概率与开关收编 publish 单口（C10 控制面 director_rules 源·默认 开/30%）——
            // B9 只管递交（participants=双方排除亲历者=转报语义），掷不掷点由控制台口径定。
            runCatching {
                newsPipeline.publish(
                    NewsEventDraft(
                        characterUuid = a.uuid,
                        characterName = a.name,
                        locationKey = loc,
                        eventSummary = description,
                        occurredAt = nowMillis,
                        sourceRaw = NEWS_SOURCE,
                        sourceRefUuid = eventKey,
                    ),
                    participants = listOf(a.uuid, b.uuid),
                    nowMillis = nowMillis,
                    random = random,
                )
            }.onFailure { Log.w(TAG, "B9 择机报道递交失败（不影响记账）: ${it.message}") }
        }
        fired
    }

    /** 相遇句模板（一两句话·轻量事件；模板优先零 LLM 成本，LLM 升级归后续）。注意：中文字符是 Kotlin
     *  合法标识符字符，插值必须用 ${} 显式花括号——`$a与` 会被解析成标识符 `a与`（编译期即炸，fail-loud）。 */
    private fun encounterSentence(random: Random, a: String, b: String, loc: String): String {
        val templates = listOf(
            "${a}与${b}在${loc}不期而遇，互通名号后各自离开",
            "${a}与${b}在${loc}短暂交锋，未分胜负便各自收手",
            "${a}在${loc}认出了${b}，两人交换了几句近况",
            "${a}与${b}在${loc}擦肩而过，彼此多看了对方一眼",
        )
        return templates[random.nextInt(templates.size)]
    }

    private companion object {
        const val TAG = "DirectorRulesScan"
        /** 新闻来源标识（publish 幂等键之一：sourceRaw+sourceRefUuid=eventKey）。 */
        const val NEWS_SOURCE = "director_rules"
    }
}
