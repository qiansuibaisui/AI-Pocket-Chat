package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.StoryEventLedgerEntity
import com.situ.aichat.morgans.NewsControlSettings
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * [zCODE] P6 切片一·导演控制台（拍板①：设置页普通入口·release 可达·每写入过确认弹窗）。
 *
 * 文案硬编码中文不进 strings.xml——沿 [KernelObservatoryScreen] 豁免先例记理由：单用户 mod 包·
 * 业主中文·工程面板不做双语（该先例为 debug-only，本屏为 release 可达，豁免口径**待终审追认**）。
 * B8 入口不在本屏（拍板③：归聊天页会话菜单·切片二）；新闻侧无任何人工催发（拍板⑤）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectorConsoleScreen(
    onBack: () -> Unit,
    viewModel: DirectorConsoleViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    // 滑杆拖动中的本地暂存（松手才 stage——确认弹窗不会跟着拖动连续弹）
    var meetingEndProb by remember(s.meetingEnd.probability) { mutableFloatStateOf(s.meetingEnd.probability.toFloat()) }
    var directorRulesProb by remember(s.directorRules.probability) { mutableFloatStateOf(s.directorRules.probability.toFloat()) }
    var b9TriggerProb by remember(s.tuning.triggerProbability) { mutableFloatStateOf(s.tuning.triggerProbability.toFloat()) }
    var personaCoefficient by remember(s.gate.personaCoefficient) { mutableFloatStateOf(s.gate.personaCoefficient.toFloat()) }

    LaunchedEffect(s.scanQueuedAt) {
        if (s.scanQueuedAt != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.consumeScanFeedback()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "导演控制台",
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── 段一 · 摩根斯通讯社（C10 触发源+用户船团兜底）──
            SettingsSection(title = "摩根斯通讯社（上报判定）") {
                SettingsSwitchRow(
                    title = "见面必报",
                    subtitle = "线下见面结束后自动上报（meeting_end）",
                    checked = s.meetingEnd.enabled,
                    onCheckedChange = { viewModel.stageTriggerEnabled("meeting_end", it) },
                )
                SettingsSliderRow(
                    label = "见面必报·概率",
                    valueLabel = "${(meetingEndProb * 100).roundToInt()}%",
                    value = meetingEndProb,
                    valueRange = 0f..1f,
                    steps = 9,
                    onValueChange = { meetingEndProb = it },
                    onValueChangeFinished = { viewModel.stageTriggerProbability("meeting_end", meetingEndProb.toDouble()) },
                )
                SettingsSwitchRow(
                    title = "B9 相遇报道",
                    subtitle = "远方相遇事件的择机上报（director_rules）",
                    checked = s.directorRules.enabled,
                    onCheckedChange = { viewModel.stageTriggerEnabled("director_rules", it) },
                )
                SettingsSliderRow(
                    label = "B9 相遇·报道概率",
                    valueLabel = "${(directorRulesProb * 100).roundToInt()}%",
                    value = directorRulesProb,
                    valueRange = 0f..1f,
                    steps = 9,
                    onValueChange = { directorRulesProb = it },
                    onValueChangeFinished = { viewModel.stageTriggerProbability("director_rules", directorRulesProb.toDouble()) },
                )
                FleetKeyField(value = s.userFleetKey, onStage = viewModel::stageUserFleetKey)
            }

            // ── 段二 · B9 判定参数 + 立即扫描 + 最近事件 ──
            SettingsSection(title = "B9 远方相遇（判定与观测）") {
                SettingsSliderRow(
                    label = "触发概率（每对每扫描·6h 一扫）",
                    valueLabel = "${(b9TriggerProb * 100).roundToInt()}%",
                    value = b9TriggerProb,
                    valueRange = 0f..1f,
                    steps = 9,
                    onValueChange = { b9TriggerProb = it },
                    onValueChangeFinished = { viewModel.stageTuning(s.tuning.copy(triggerProbability = b9TriggerProb.toDouble())) },
                )
                SettingsSwitchRow(
                    title = "每日全库上限",
                    subtitle = "当前 ${s.tuning.dailyCap} 起",
                    checked = s.tuning.dailyCap > 0,
                    onCheckedChange = { on ->
                        viewModel.stageTuning(s.tuning.copy(dailyCap = if (on) NewsControlSettings.DirectorTuning().dailyCap else 0))
                    },
                )
                SettingsSwitchRow(
                    title = "单对冷却（72h）",
                    subtitle = "同对冷却窗内不再触发 · 当前 ${s.tuning.pairCooldownMs / 3600_000}h",
                    checked = s.tuning.pairCooldownMs > 0,
                    onCheckedChange = { on ->
                        viewModel.stageTuning(s.tuning.copy(pairCooldownMs = if (on) NewsControlSettings.DirectorTuning().pairCooldownMs else 0L))
                    },
                )
                SettingsSwitchRow(
                    title = "用户代理时效（24h·fail-closed）",
                    subtitle = "最近线下见面超时即全局静默 · 当前 ${s.tuning.proxyMaxAgeMs / 3600_000}h",
                    checked = s.tuning.proxyMaxAgeMs > 0,
                    onCheckedChange = { on ->
                        viewModel.stageTuning(s.tuning.copy(proxyMaxAgeMs = if (on) NewsControlSettings.DirectorTuning().proxyMaxAgeMs else 0L))
                    },
                )
                Button(
                    onClick = viewModel::requestScanNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (s.scanQueuedAt == null) "立即扫描（同 worker 路径）" else "已入队，等待执行…")
                }
                StatLine("今日已触发：${s.b9.todayCount} 起 · 最近：${timeText(s.b9.lastAtMillis)}")
                StatLine("B8 文游（只读·入口在聊天页）：${s.b8.recentTurnCount} 回合 · 最近：${timeText(s.b8.lastAtMillis)} · 会话位：${kindText(s.b8.conversationKind)}")
                RecentEventList(s.recentEvents)
            }

            // ── 段三 · 发射闸（C11）──
            SettingsSection(title = "主动消息发射闸（C11）") {
                SettingsSwitchRow(
                    title = "发射闸总开关",
                    subtitle = "默认关=现行为（F3 禁令随闸生效：开=陌生卡不得发起主动消息）",
                    checked = s.gate.enabled,
                    onCheckedChange = { viewModel.stageGate(s.gate.copy(enabled = it)) },
                )
                SettingsSwitchRow(
                    title = "拦截陌生卡",
                    subtitle = "关系未建档（INITIAL）的角色不发",
                    checked = s.gate.strangerBlock,
                    onCheckedChange = { viewModel.stageGate(s.gate.copy(strangerBlock = it)) },
                )
                SettingsSliderRow(
                    label = "人设系数",
                    valueLabel = personaCoefficient.roundToInt().toString(),
                    value = personaCoefficient,
                    valueRange = 0f..2f,
                    steps = 3,
                    infoMessage = "主动消息活跃度的人设放大系数。0=全禁，1=常态，2=话痨。写库经确认弹窗。",
                    onValueChange = { personaCoefficient = it },
                    onValueChangeFinished = { viewModel.stageGate(s.gate.copy(personaCoefficient = personaCoefficient.toDouble())) },
                )
                StatLine("情报白名单：${if (s.gate.intelWhitelist.isEmpty()) "（空=零 bypass）" else s.gate.intelWhitelist.joinToString("、")}")
                Text(
                    "白名单编辑（角色名或 UUID，逗号分隔）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                WhitelistField(initial = s.gate.intelWhitelist, onStage = { list -> viewModel.stageGate(s.gate.copy(intelWhitelist = list)) })
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 确认弹窗（拍板①防误触+审计预览同源）
    s.pending?.let { pending ->
        AppDialog(
            onDismissRequest = viewModel::dismissPending,
            title = "确认修改",
            body = "${pending.label}\n\n旧：${pending.oldText}\n新：${pending.newText}",
            confirmText = "写入",
            onConfirm = viewModel::confirmPending,
            dismissText = "取消",
            onDismiss = viewModel::dismissPending,
        )
    }
}

@Composable
private fun StatLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun FleetKeyField(value: String, onStage: (String) -> Unit) {
    var text by remember(value) { androidx.compose.runtime.mutableStateOf(value) }
    Text(
        "用户船团兜底键（空=自动代理：最近线下见面→该角色锚点）",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        AppTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f))
        Button(onClick = { onStage(text) }, modifier = Modifier.padding(start = 8.dp)) { Text("暂存") }
    }
}

@Composable
private fun WhitelistField(initial: List<String>, onStage: (List<String>) -> Unit) {
    var text by remember(initial) { androidx.compose.runtime.mutableStateOf(initial.joinToString(",")) }
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        AppTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f))
        Button(
            onClick = { onStage(text.split("，", ",").map { it.trim() }.filter { it.isNotEmpty() }) },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("暂存") }
    }
}

@Composable
private fun RecentEventList(events: List<StoryEventLedgerEntity>) {
    if (events.isEmpty()) {
        StatLine("最近导演事件：暂无（dir-r:/dir-m:）")
        return
    }
    StatLine("最近导演事件（前 ${events.size} 条）：")
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        events.take(10).forEach { e ->
            Text(
                "${timeText(e.completedAt)} · ${if (e.eventKey.startsWith("dir-r:")) "相遇" else "文游"} · ${e.description.ifBlank { e.eventKey }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

private fun timeText(millis: Long?): String =
    millis?.let {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    } ?: "从未"

private fun kindText(kind: DirectorConsoleViewModel.ConversationKind): String = when (kind) {
    DirectorConsoleViewModel.ConversationKind.PRIVATE -> "私聊（群聊位预留）"
    DirectorConsoleViewModel.ConversationKind.GROUP -> "群聊"
}
