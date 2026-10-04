package com.situ.aichat.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.local.dao.StoryStateDao
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import com.situ.aichat.director.DirectorRulesScanWorker
import com.situ.aichat.morgans.NewsControlSettings
import com.situ.aichat.work.BackgroundScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * [zCODE] P6 切片一·导演控制台 ViewModel（五拍板 2026-10-04 全按默认案）。
 *
 * 三段：摩根斯（触发源+概率/用户船团兜底）·B9（四数+立即扫描+最近事件）·发射闸（C11 四字段）。
 * 交互纪律（拍板①补条款）：**每次写入必过确认弹窗**——弹窗文案与审计行同源（键名+旧值→新值），
 * 确认即写入+留痕（DataStore 无痕，审计行进 Logcat·tag=[DirectorConsoleAudit.TAG]）。
 * B8 只读状态区（拍板③）：事件数+最近触发时间，入口不进控制台（归聊天页会话菜单·切片二）。
 *
 * P5-预埋①（会话抽象纪律）：[ConversationKind] 枚举位今日留好，群聊落地前恒 PRIVATE，成本零。
 */
@HiltViewModel
class DirectorConsoleViewModel @Inject constructor(
    private val newsControl: NewsControlSettings,
    private val storyStateDao: StoryStateDao,
    private val scheduler: BackgroundScheduler,
) : ViewModel() {

    /** P5-预埋①：B8 统计按会话类型分列的枚举位。群聊（P5）落地后由会话数据源填充，今日恒 [PRIVATE]。 */
    enum class ConversationKind { PRIVATE, GROUP }

    data class B9Stats(val todayCount: Int, val lastAtMillis: Long?)

    /** B8 状态区数据（拍板③：只读；conversationKind=P5-预埋① 枚举位）。 */
    data class B8Stats(val recentTurnCount: Int, val lastAtMillis: Long?, val conversationKind: ConversationKind = ConversationKind.PRIVATE)

    data class ConsoleState(
        val loaded: Boolean = false,
        val meetingEnd: NewsControlSettings.SourceTrigger = NewsControlSettings.SourceTrigger(enabled = true, probability = 1.0),
        val directorRules: NewsControlSettings.SourceTrigger = NewsControlSettings.SourceTrigger(enabled = true, probability = 0.30),
        val userFleetKey: String = "",
        val tuning: NewsControlSettings.DirectorTuning = NewsControlSettings.DirectorTuning(),
        val gate: NewsControlSettings.ProactiveGate = NewsControlSettings.ProactiveGate(),
        val b9: B9Stats = B9Stats(0, null),
        val b8: B8Stats = B8Stats(0, null),
        val recentEvents: List<StoryEventLedgerEntity> = emptyList(),
        val pending: PendingChange? = null,
        /** 立即扫描已入队的回执闪现（UI 显示后自行清除）。 */
        val scanQueuedAt: Long? = null,
    )

    /**
     * 待确认变更：[apply] 持写入闭包（confirm 时才执行）；[key]/[oldText]/[newText] 三元组
     * 同时喂确认弹窗与审计行——弹窗即审计预览，两处永不漂移。
     */
    data class PendingChange(
        val label: String,
        val key: String,
        val oldText: String,
        val newText: String,
        val apply: suspend (NewsControlSettings) -> Unit,
    )

    private val _state = MutableStateFlow(ConsoleState())
    val state: StateFlow<ConsoleState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val triggers = newsControl.readTriggers()
            val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val b9Today = storyStateDao.directorRulesEventCountSince(dayStart)
            val b8Count = storyStateDao.ledgerCountByKeyPrefixSince(DIRECTOR_TEXT_EVENT_PREFIX, 0L)
            val recent = runCatching { storyStateDao.recentDirectorLedger(RECENT_EVENT_LIMIT) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loaded = true,
                    meetingEnd = triggers["meeting_end"] ?: NewsControlSettings.defaultTriggers["meeting_end"] ?: it.meetingEnd,
                    directorRules = triggers["director_rules"] ?: NewsControlSettings.defaultTriggers["director_rules"] ?: it.directorRules,
                    userFleetKey = newsControl.userFleetKeyOverride(),
                    tuning = newsControl.directorTuning(),
                    gate = newsControl.proactiveGate(),
                    b9 = B9Stats(b9Today, recent.lastAt(DIRECTOR_RULES_PREFIX)),
                    b8 = B8Stats(b8Count, recent.lastAt(DIRECTOR_TEXT_PREFIX)),
                    recentEvents = recent,
                )
            }
        }
    }

    // ── 暂存（不写库）──每动作只造 PendingChange，写入一律等 confirmPending()

    fun stageTriggerEnabled(sourceRaw: String, enabled: Boolean) {
        val current = if (sourceRaw == "meeting_end") _state.value.meetingEnd else _state.value.directorRules
        stage(
            label = "触发源[${sourceLabel(sourceRaw)}]·开关",
            key = "trigger.$sourceRaw.enabled",
            old = current.enabled.toString(), new = enabled.toString(),
        ) { it.writeTrigger(sourceRaw, current.copy(enabled = enabled)) }
    }

    fun stageTriggerProbability(sourceRaw: String, probability: Double) {
        val current = if (sourceRaw == "meeting_end") _state.value.meetingEnd else _state.value.directorRules
        stage(
            label = "触发源[${sourceLabel(sourceRaw)}]·上报概率",
            key = "trigger.$sourceRaw.probability",
            old = PERCENT.format(current.probability), new = PERCENT.format(probability),
        ) { it.writeTrigger(sourceRaw, current.copy(probability = probability)) }
    }

    fun stageUserFleetKey(value: String) {
        val v = value.trim()
        stage(
            label = "用户船团兜底键",
            key = "userFleetKeyOverride",
            old = _state.value.userFleetKey.ifEmpty { "（空=自动代理）" },
            new = v.ifEmpty { "（空=自动代理）" },
        ) { it.writeUserFleetKeyOverride(v) }
    }

    fun stageTuning(tuning: NewsControlSettings.DirectorTuning) {
        val old = _state.value.tuning
        if (old == tuning) return
        stage(
            label = "B9 判定参数",
            key = "directorTuning",
            old = tuningSummary(old), new = tuningSummary(tuning),
        ) { it.writeDirectorTuning(tuning) }
    }

    fun stageGate(gate: NewsControlSettings.ProactiveGate) {
        val old = _state.value.gate
        if (old == gate) return
        stage(
            label = "发射闸（C11）",
            key = "proactiveGate",
            old = gateSummary(old), new = gateSummary(gate),
        ) { it.writeProactiveGate(gate) }
    }

    fun dismissPending() = _state.update { it.copy(pending = null) }

    /** 确认门控：唯一写入口。写→审计留痕→重载。 */
    fun confirmPending() {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        viewModelScope.launch {
            pending.apply(newsControl)
            Log.i(DirectorConsoleAudit.TAG, DirectorConsoleAudit.line(pending.key, pending.oldText, pending.newText))
            refresh()
        }
    }

    /** 立即扫描（拍板④：同 worker 代码路径·一次性入队 REPLACE；幂等由 scanOnce 既有防呆兜底）。 */
    fun requestScanNow() {
        scheduler.scheduleOneShot(
            uniqueName = DirectorRulesScanWorker.UNIQUE_MANUAL,
            workerClass = DirectorRulesScanWorker::class.java,
            requireNetwork = false,
            existingPolicy = ExistingWorkPolicy.REPLACE,
        )
        _state.update { it.copy(scanQueuedAt = System.currentTimeMillis()) }
    }

    fun consumeScanFeedback() = _state.update { it.copy(scanQueuedAt = null) }

    private fun stage(
        label: String,
        key: String,
        old: String,
        new: String,
        apply: suspend (NewsControlSettings) -> Unit,
    ) {
        if (old == new) return
        _state.update { it.copy(pending = PendingChange(label, key, old, new, apply)) }
    }

    private fun sourceLabel(sourceRaw: String): String = when (sourceRaw) {
        "meeting_end" -> "见面必报"
        "director_rules" -> "B9 相遇"
        else -> sourceRaw
    }

    private fun tuningSummary(t: NewsControlSettings.DirectorTuning): String =
        "概率${PERCENT.format(t.triggerProbability)}/日上限${t.dailyCap}/对冷却${t.pairCooldownMs / 3600_000}h/代理时效${t.proxyMaxAgeMs / 3600_000}h"

    private fun gateSummary(g: NewsControlSettings.ProactiveGate): String =
        "开关${if (g.enabled) "开" else "关"}/拦陌生${if (g.strangerBlock) "开" else "关"}/人设系数${g.personaCoefficient}/白名单${g.intelWhitelist.size}人"

    private fun List<StoryEventLedgerEntity>.lastAt(prefix: String): Long? =
        firstOrNull { it.eventKey.startsWith(prefix) }?.completedAt

    companion object {
        private const val DIRECTOR_RULES_PREFIX = "dir-r:"
        private const val DIRECTOR_TEXT_PREFIX = "dir-m:"
        private const val RECENT_EVENT_LIMIT = 20

        /** ledgerCountByKeyPrefixSince 的前缀实参（LIKE 'dir-m:%'）。 */
        internal const val DIRECTOR_TEXT_EVENT_PREFIX = "dir-m:%"
        private val PERCENT = java.text.DecimalFormat("0.#%")
    }
}

/** 写入留痕（拍板外施工建议·业主 2026-10-04 采纳）：一行审计=键名+旧值→新值，与确认弹窗同源。 */
object DirectorConsoleAudit {
    const val TAG = "DirectorConsole"

    fun line(key: String, old: String, new: String): String = "write key=$key old=$old new=$new"
}
