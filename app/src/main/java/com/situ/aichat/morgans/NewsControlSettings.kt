package com.situ.aichat.morgans

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.situ.aichat.director.DirectorRulesConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [zCODE] 中继3·C10 控制面设置（项1/3/4·拍板③：初始默认=现行为，业主装包后逐条手动调）。
 *
 * 选型（勘察问答一）：复用既有 DataStore<Preferences> 基建（SettingsRepository 同源注入）+ JSON blob 范式
 * （同 KEY_PROMPT_MODULES 先例）——**零 schema 变更零迁移**。压级与知情名单共用同一 blob（一个配置入口两类字段·拍板①）。
 *
 * 默认值表（=中继2 收货时的现行为，逐条保真）：
 * - 触发源：meeting_end=开/1.0（每次必报）；director_rules=开/0.30（B9 30%）；
 * - DirectorRules 五数：DirectorRulesConfig 常量即默认（判定类参数迁此处生效；SCAN_INTERVAL_MS 为 WorkManager
 *   排程结构参数，改值需重排任务——保持常量，不进本设置面）；
 * - 发射闸（项5v2）：默认关（enabled=false=现行为·F3 禁令由业主开启）+情报白名单空表=零 bypass。
 */
@Singleton
class NewsControlSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** 每源触发配置（开关+概率·拍板③）。 */
    @Serializable
    data class SourceTrigger(val enabled: Boolean = true, val probability: Double = 1.0)

    /** 单事件手动配置（项3 压级+项4 知情名单·同一入口两类字段）。detailOverride：2 详版/1 概述/0 模糊/-1 零知晓。 */
    @Serializable
    data class EventOverride(
        val detailOverride: Int? = null,
        val allowList: List<String> = emptyList(),
    )

    /** B9 判定参数（DirectorRulesConfig 常量=默认值）。 */
    @Serializable
    data class DirectorTuning(
        val triggerProbability: Double = DirectorRulesConfig.TRIGGER_PROBABILITY,
        val dailyCap: Int = DirectorRulesConfig.DAILY_CAP,
        val pairCooldownMs: Long = DirectorRulesConfig.PAIR_COOLDOWN_MS,
        val proxyMaxAgeMs: Long = DirectorRulesConfig.PROXY_MAX_AGE_MS,
    )

    /** 主动消息发射闸（项5v2·C11）。默认 enabled=false=现行为（闸门随 F3 禁令由业主开启）。 */
    @Serializable
    data class ProactiveGate(
        val enabled: Boolean = false,
        val strangerBlock: Boolean = true,
        val personaCoefficient: Double = 1.0,
        val intelWhitelist: List<String> = emptyList(),
    )

    /** 触发源默认表（=现行为）。未知源默认 开/1.0（与"见面必报"同口径，控制台可关）。伴生级=免实例可测。 */
    companion object {
        val defaultTriggers: Map<String, SourceTrigger> = mapOf(
            "meeting_end" to SourceTrigger(enabled = true, probability = 1.0),
            "director_rules" to SourceTrigger(enabled = true, probability = DirectorRulesConfig.NEWS_REPORT_PROBABILITY),
        )
    }

    /** 纯编解码（金样测默认表与 blob 对称性·不触 DataStore）。 */
    object Blobs {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val triggerMap = MapSerializer(String.serializer(), SourceTrigger.serializer())
        private val overrideMap = MapSerializer(String.serializer(), EventOverride.serializer())

        fun encodeTriggers(map: Map<String, SourceTrigger>): String = json.encodeToString(triggerMap, map)
        fun decodeTriggers(s: String): Map<String, SourceTrigger> =
            runCatching { json.decodeFromString(triggerMap, s) }.getOrDefault(emptyMap())

        fun encodeOverrides(map: Map<String, EventOverride>): String = json.encodeToString(overrideMap, map)
        fun decodeOverrides(s: String): Map<String, EventOverride> =
            runCatching { json.decodeFromString(overrideMap, s) }.getOrDefault(emptyMap())
    }

    private val keyTriggers = stringPreferencesKey("news_control_triggers")
    private val keyOverrides = stringPreferencesKey("news_control_event_overrides")
    private val keyUserFleetOverride = stringPreferencesKey("news_control_user_fleet_override")
    private val keyDirectorTuning = stringPreferencesKey("news_control_director_tuning")
    private val keyProactiveGate = stringPreferencesKey("news_control_proactive_gate")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** 每源触发配置（blob 覆盖默认表；无配置=默认=现行为）。 */
    suspend fun triggerFor(sourceRaw: String): SourceTrigger =
        readTriggers()[sourceRaw] ?: defaultTriggers[sourceRaw] ?: SourceTrigger()
    suspend fun readTriggers(): Map<String, SourceTrigger> =
        Blobs.decodeTriggers(dataStore.data.first()[keyTriggers] ?: "")

    suspend fun writeTrigger(sourceRaw: String, trigger: SourceTrigger) {
        dataStore.edit { p ->
            val cur = Blobs.decodeTriggers(p[keyTriggers] ?: "").toMutableMap()
            cur[sourceRaw] = trigger
            p[keyTriggers] = Blobs.encodeTriggers(cur)
        }
    }

    /** 单事件手动配置（压级+知情名单）。null=清除（回默认判定）。 */
    suspend fun eventOverrideFor(sourceRefUuid: String): EventOverride? =
        readOverrides()[sourceRefUuid]

    suspend fun readOverrides(): Map<String, EventOverride> =
        Blobs.decodeOverrides(dataStore.data.first()[keyOverrides] ?: "")

    suspend fun writeEventOverride(sourceRefUuid: String, override: EventOverride?) {
        dataStore.edit { p ->
            val cur = Blobs.decodeOverrides(p[keyOverrides] ?: "").toMutableMap()
            if (override == null) cur.remove(sourceRefUuid) else cur[sourceRefUuid] = override
            p[keyOverrides] = Blobs.encodeOverrides(cur)
        }
    }

    /** 用户船团兜底（中继2 剧本第8步预告兑现：出常量入设置）。 */
    suspend fun userFleetKeyOverride(): String =
        dataStore.data.first()[keyUserFleetOverride] ?: DirectorRulesConfig.USER_FLEET_KEY_OVERRIDE

    suspend fun writeUserFleetKeyOverride(value: String) {
        dataStore.edit { it[keyUserFleetOverride] = value }
    }

    /** B9 判定参数（默认=常量现值）。 */
    suspend fun directorTuning(): DirectorTuning =
        dataStore.data.first()[keyDirectorTuning]?.let {
            runCatching { json.decodeFromString(DirectorTuning.serializer(), it) }.getOrNull()
        } ?: DirectorTuning()

    suspend fun writeDirectorTuning(tuning: DirectorTuning) {
        dataStore.edit { it[keyDirectorTuning] = json.encodeToString(DirectorTuning.serializer(), tuning) }
    }

    /** 发射闸配置（项5v2·默认关=现行为）。 */
    suspend fun proactiveGate(): ProactiveGate =
        dataStore.data.first()[keyProactiveGate]?.let {
            runCatching { json.decodeFromString(ProactiveGate.serializer(), it) }.getOrNull()
        } ?: ProactiveGate()

    suspend fun writeProactiveGate(gate: ProactiveGate) {
        dataStore.edit { it[keyProactiveGate] = json.encodeToString(ProactiveGate.serializer(), gate) }
    }
}
