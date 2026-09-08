<!-- [zCODE] 新增文件：P0-第1项 运维观测项交付——内核观测台两处机制说明 + 四标记解析器畸形输入现状记录 -->

# 内核观测台机制说明（P1 运维观测项）

观测台入口：设置 → 内核观测台（仅 debug，路由 `kernelObservatory`，`ui/liuli/settings/LiuliKernelObservatoryScreen.kt`；行渲染纯函数 `observatoryLines` :127 起）。行序：越顶标记 → 关系 → 正压 → 负压 → 四场 → **预算** → **命中** → **意图** → 性格 → 名分 → 上次分析 → 下次窗口。

## 一、「预算 0/40 但有更新」

观测台该行（`LiuliKernelObservatoryScreen.kt:144`）：

```
预算：今日已用 {budgetUsed}/{DAILY_BUDGET=40}   上次更新：{updatedAt}
```

两个字段的**写入方不同**，这是现象的根因：

| 字段 | 写入方 | 触发 |
|---|---|---|
| `updatedAt` | `AffectKernel.tick`（聊天/语音每轮回合尾） | 每轮聊天都刷新（松弛 + 激活脉冲 + 跨日归零） |
| `budgetUsed` | `GrowthAnalysisCoordinator` 步骤 5（后台分析通道） | 仅当分析产出有效的 16 维位移时消耗（K-3：日位移预算只管分析通道 16 维，**四场不进池**，`prompt/growth/RelationshipBands.kt:101`） |

因此「预算 0/40 + 上次更新时间很新」= **聊天在正常喂场（tick 活着），但当日分析通道没有消耗过预算**。三条正常路径：

1. **分析窗口未到**：成长分析按「距上次分析轮数 / 新内容量」触发（观测台「下次窗口预估」行可查），当天没到窗口就不跑分析；
2. **分析跑了但零位移**：分析输出缺席/非法 key 被忽略（`GrowthAnalysisCoordinator.kt:342`「缺席 = 0、非法 key 忽略」）或 16 维净位移恰为 0——不消耗预算（四场变化也不耗）；
3. **跨日归零**：`AffectMath.kt:183` 每日首次 tick 会把预算归零 + `budgetDayStart` 置今日零点，午夜后看到 0/40 属正常。

**排障口径**：看同一屏「上次分析」行——时间戳新而预算 0 → 路径 2（分析产出质量问题，对照分析日志看 DeepSeek v4-flash 的原始输出）；时间戳旧 → 路径 1/3。若怀疑分析根本没跑（DeepSeek 配额/Key 失败），查分析通道日志（不在此屏）。

## 二、「命中非空 / 有时间，但意图为空」

- **命中行**（`field.hits` + `hitsAt`）：`hits` = **最近一次成长分析**识别出的命中项快照（`gNN` 系统项 + 字面 `bandUp`，`data/model/CharacterKernelTypes.kt:167`），供算子 c07–c09/c12 在 24h 内求值；`hitsAt` 非空才显示「N h 前」，否则「—」。
- **意图行**（`intentQueue`）：当前挂着的 live 意图，由 `IntentKernel.birth`（`prompt/growth/IntentKernel.kt:172`）**在分析通道里萌生**，且有门控：
  - 每次分析最多 `MAX_BIRTHS_PER_ANALYSIS` 个；
  - 同 kind RESOLVED 后 24h 冷却 / FADED 后 3 天冷却（E40，防同一批命中每周重生同一句）；
  - 强度按命中集合最高档取 40/50/60（K-22），低于萌生门槛不生；
  - 已萌生的意图会半衰期消退、全清词表了结——消退后意图行回空，但 `hits` 快照仍在 24h 窗口内。

**结论**：命中 ≠ 必然萌生意图——「命中非空 + 意图为空」是**门控/冷却/消退的正常语义**，不是丢失。仅当命中含高强度项且无冷却、意图行仍持续为空时才值得按 bug 挂账。

## 三、（附加条件 c）四标记解析器畸形输入现状记录

P1 勘察逐个核对的结论——四个文本标记解析器**现状已全部容错，畸形输入静默降级、不炸回合**，P1 不改其调用（`OutputSanitizer.parseBlockTolerantly` 作为第 2 项锚点块的统一容错入口预置）：

| 解析器 | 位置 | 畸形输入现状行为 |
|---|---|---|
| `CalendarAction.parseFromResponse` | `data/calendar/CalendarAction.kt:197-208` | 单条 JSON 解码失败（含未知 action 枚举）runCatching 静默跳过该条；**全部标签无论合法与否都从正文剥除**；永不抛 |
| `OfflineMeetingAction.parseFromResponse` | `offline/OfflineMeetingAction.kt:267+` | 纯 regex 析构（无 JSON）——畸形即不匹配、无动作；标记照剥；永不抛。注意：结构化路的 `fromToolCallArguments`（:193-195）对非法 JSON **会抛**，由工具调用侧异常处理兜底，属结构化路既有语义 |
| `FutureMeetingTool.parseProposalMarkers` | `meeting/FutureMeetingTool.kt:90-112` | 标记后无合法 JSON → 只擦裸标记（注释明示「绝不让 [future_meeting] 泄露」）；JSON 解码失败 → 擦整段 + 丢该候选；永不抛 |
| `PromiseChatTool.parseMarkers` | `promise/PromiseChatTool.kt:134-156` | 同上同款（标记后无 JSON → 擦裸标记；解码失败 → 擦整段丢动作）；永不抛 |
