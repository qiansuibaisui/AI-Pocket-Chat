<!-- [zCODE] 新增文件：P4 B组交棒文档——上下文将满，工程状态全量快照入仓 -->

# HANDOVER · P4 B组交棒文档（2026-10-01）

## ① 工程台账

### 缺陷史

| ZD | 级别 | 根因 | 修复 | 状态 |
|---|---|---|---|---|
| ZD-9 | P0 数据告急 | `MIGRATION_52_53` 定义于 Migrations.kt:791 但 `ALL_MIGRATIONS` 数组止于 `MIGRATION_51_52`(:853)——v52→v53 无迁移→Room 抛→秒崩 | `aa65083`：数组追加一行 | ✅ 销案 |
| ZD-10 | P0 功能 | 闸门单卡点——生成时 A5 拦截、读料时敞着→存量脏日程经 prompt 复述+写进新锚点在场名单 | `a98abc8`+`085cf8a`：Engine+余温管线读料侧重跑闸门（双卡） | ✅ 施工闭合·待真机三验 |
| ZD-11 | P1 归因 | 余温管线日程渲染第二人称（"你今天…"）→模型将角色日程复读成用户行为（威士忌三连） | `6db6fcb`：system 消息后处理 6 组替换改三人称；`33731c9`：fail-loud+全量扫描 | ✅ 施工闭合·待真机复验 |
| ZD-12 | P0 数据丢失 | connectedDebugAndroidTest 的 UTP 流程对同 applicationId 的 release/mod 签名包**先卸载再装 debug 测试包**（异签名必先卸载）→应用+数据全失；华为备份该应用 0KB 确认不可恢复；原始包 com.situ.aichat 完好未动 | 无代码修复位——流程防线（惯例②修订版）+大考素材转净库口径（见真机验收表）；建团 UI 同场定性：数据门控非构建缺失 | ✅ 入账·口径已重定义 |

### Commit 链（P4 前置件+B组 全量）

```
9d1a322  前置件0收口（宏解析改主宏映射）
7be96ae  A组部分收口（relations表+A1/A3+金样9例）
f9764c7  A组收口（闸门接线+A7 news并入+A2宏断言）
3d1916c  A组真收口（名→UUID+管线级样本#1/#2·占位移除）
b2ea491  P2修补批（传播边界+自愈回落+主体绑定）
aa65083  ZD-9修复（MIGRATION_52_53数组追加）
34fec8b  B组#2+#4（广播即时失据+SHA-256补账）
a98abc8  B组#5+#6（闸门双卡读料侧+在场一致性评估）
085cf8a  B组#5路径确认+工单2回归+余温三人称+暗门修复
6db6fcb  ZD-11注入结构改造（system消息后处理三组替换）
33731c9  ZD-11防脆化（fail-loud+全量扫描3处补入）
88e4b25  工单#1（锚点双层格式：全团层解析/注入/落库+迁移53→54·数组已追加+A3一致性闸门+金样3+1）
```

### 包史

| 包 | commit | 日期 | 大小 | SHA-256（文件） |
|---|---|---|---|---|
| P2批一出包 | 8169d12 | Sep 21 | 225,877,081 | 未记录（致歉） |
| P2批一修正 | 8169d12 | Sep 22 | 225,877,081 | 未记录 |
| P2批一LB-6b | 8169d12 | Sep 22 | 同上 | 同上 |
| P3出包 | abc0203 | Sep 29 | 225,893,465 | 未记录 |
| P2修补批出包 | b2ea491 | Sep 30 | 225,926,233 | `21f03e6644…8dbf90`（ZD-9 崩溃包） |
| ZD-9修复出包 | aa65083 | Sep 30 | 225,926,233 | 未记录（同大小·用户实测已通） |
| 三合一大考包 | 88e4b25（HEAD=6fe24b6 docs+1） | Oct 1 | 225,926,233 | 文件 `47719275c9d3f4f995f2b21f04fd13de9060e37fd56879b7d703720e47f4f7b6`·证书 `922c5fe7f41748dcdb5eed4eed628adb7339e92f429275a1bef7a7107598ae55`（v2 签名·CN=AI Pocket Chat Mod·RSA4096）·包内已验 #1 特征串（fleetLayerJson/全团：/某海域）·**MigrationTest 缓跑挂账**（2026-10-01 用户裁定：v53→54 真实存量场景已随 ZD-12 消失·转常规回归项验迁移链代码正确性·不阻塞大考） |

## ② 工单 #1 完整规格（含附加两项）

**锚点格式双层规范化** + **防脆化已做项**（fail-loud+全量扫描已入 `33731c9`）

**格式修订版（终版·覆盖此前草案）**：
```
【锚点】全团：{团名}·{海域}·{港口/锚地}（{船名}·{MotionState}）｜个人：{个人位置}｜在场：{名单}｜节点：{事件}
```

**MotionState 字段化**：
| MotionState | 全团层地点写法 | 粒度 |
|---|---|---|
| 停靠 | `（莫比迪克号·停靠）` | 精确到港口/锚地 |
| 航行中 | `（莫比迪克号·航行中）` | 退到海域级（"新世界·某海域"） |
| 锚泊/漂泊 | `（莫比迪克号·锚泊）` | 海域级+状态注明 |

**施工落点**：
| 件 | 改动 |
|---|---|
| `AnchorBlockParser` | 解析"全团："前缀→提取团名+海域+港口+船名+MotionState |
| `AnchorInjectionBuilder` | 注入时渲染双层（全团层+个人层） |
| prompt 指导 | 认知边界六.2 或锚点注入段加格式示例+MotionState 粒度联动说明 |
| 金样 3+1 条 | ①两种角色锚点均含全团层前缀 ②航行中=海域级粒度 ③MotionState 不一致 A3 拦截（锚点=航行中+日程=登陆→拦） ④防脆化漂移样本 |

**已完成的防脆化项**（`33731c9`）：
- 替换逻辑 fail-loud：`Regex("你(今天完整|…)")` 替换后残留→`IllegalStateException`（禁止静默跳过）
- 二人称全量扫描：`PromptBuilderSchedule` 排查完毕——6 处已全部覆盖（你今天完整的日程/你的本人行程/【此刻】你正在/你刚结束/你可能在放松/你此刻正在），不存在第七处

## ③ 惯例与已证伪清单

### 已生效惯例

| 惯例 | 内容 | 来源 |
|---|---|---|
| 数组已追加自检 | 新增 DB migration 时 commit message 必含"数组已追加" | ZD-9 复盘 |
| MigrationTest 必跑 | 出包前必跑（设备/模拟器），执行记录写进出包回执；**connectedAndroidTest 前必须确认应用数据已备份（或已明确接受清空）且用户已知悉测试流程含破坏性卸载**（UTP 对同 applicationId 异签名包先卸载） | ZD-9 复盘+ZD-12 修订 |
| 计数口径 | 全量数以 Gradle `--tests` 官方输出为准，报告附增删计数行 | P3 归因销案 |
| 行为金样 | 行为修复类金样断言**行为**而非断言**补丁存在** | ZD-11 复盘 |
| 出包双报 | 文件 SHA-256 + 证书 SHA-256 双报 | ZD-9 补账 |
| MockK answers | `coVerify` 块断言不稳→用 `answers + firstArg` 执行期捕获 | P3 中继1 |
| 长命令后台化 | 预期 >2min 的命令一律 Start-Process 分离+日志轮询，禁止前台等待 | SOP |
| 构建不并行 | 两个 gradle 构建禁止并行（build-cache 锁竞争） | SOP |

### 已证伪清单（死路·不再走）

| 死路 | 证据 | 替代方案 |
|---|---|---|
| 约束层叮嘱修法（"你日程是你本人的"） | ZD-11 威士忌三连——叮嘱打不赢语境 | 注入结构改造（第三人称渲染） |
| 空库 migration 验证 | ZD-9——MigrationTest 不在构建管线=纸面必跑 | 设备/模拟器必跑+回执记录 |
| 个人锚点全团广播 | 佩罗娜被覆写"甲板"——个人位置≠全团位置 | 传播边界切断（仅全团级事件触发） |
| capture/withArg 在 coVerify | MockK relaxed 偶发不捕获 | answers+firstArg |

## ④ 关键文件坐标表

| 功能 | 文件 | 关键行 |
|---|---|---|
| 迁移注册 | `data/local/Migrations.kt` | ALL_MIGRATIONS 数组 ~:802 |
| DB 版本 | `data/local/AppDatabase.kt` | version = 53 (:168) |
| DB 构建 | `di/DatabaseModule.kt` | databaseBuilder+addMigrations (:54-59) |
| 锚点解析 | `prompt/AnchorBlockParser.kt` | parseBlocks:68 |
| 锚点词表 | `prompt/AnchorVocabulary.kt` | normalize:67, MotionState 枚举 |
| 岛名常量 | `prompt/AnchorVocabulary.kt` | ISLAND_TOP_LEVELS |
| 认知边界 | `prompt/CognitiveBoundaryInjection.kt` | FULL_TEXT 常量 |
| 养女条目 | `prompt/CognitiveBoundaryInjection.kt` | FosterDaughterEntry.BLOCK |
| 注入组装 | `prompt/PromptBuilder.kt` | combinedBlock ~:425 |
| 关系闸门 | `schedule/RelationGateService.kt` | filterScheduleEvents:33, levelBetween:50 |
| 闸门接线（生成侧） | `prompt/schedule/ScheduleGenerationService.kt` | gatedEvents ~:162 |
| 闸门接线（读料侧·Engine） | `ui/chat/AssistantTurnEngine.kt` | gatedScheduleEvents ~:272 |
| 闸门接线（读料侧·余温） | `offline/OfflineAfterglowPromptAssembler.kt` | gatedScheduleEvents ~:95 |
| ZD-11 三人称替换 | `offline/OfflineAfterglowPromptAssembler.kt` | replace chain+fail-loud ~:143 |
| ZD-11 兜底叮嘱 | `offline/OfflineAfterglowService.kt` | 主体绑定行 ~:126 |
| 新闻管道 | `morgans/NewsPipelineService.kt` | publish:72, calculateDeliveryPlan:107 |
| 自愈回落 | `ui/character/CharacterProfileViewModel.kt` | deriveAnchorLine ~:375 |
| 传播边界（已切断） | `ui/chat/ChatReplyDeliverer.kt` | 广播调用注释 ~:309 |
| 手动关系登记 | `data/local/entity/CharacterRelationEntity.kt` | 新表 v53 |
| 建团 UI | `ui/character/CharacterEditScreen.kt` | FleetMembershipSection ~:590 |
| 主动消息接地 | `proactive/ProactiveAnchorGrounding.kt` | build+groundingFor |
| 防复读 | `prompt/AnchorInjectionBuilder.kt` | 【防复读】段 |

## ⑤ 待办与观察项

### 待施工

| 项 | 量级 | 前置 |
|---|---|---|
| **#1 锚点格式双层规范化** | ✅ 施工闭合（`88e4b25`）·待真机验收 | 全量回归已过（下行） |
| 全量回归（#1 后） | ✅ 一轮已跑（2026-10-01·Gradle 官方计数）：9244 例·失败 10·**全部存量红**（干净 HEAD 复跑同 10 例：StoryNarrativeInjectionTest 3 + ToolCallingPromptAssemblyGoldenTest 5 + OurDaysViewModelTest 1 + StoryShareCardRendererTest 1——环境性/基线陈旧·#1 新增 0 失败） | — |
| **三合一大考包出包** | ✅ 出包完成（2026-10-01·双报SHA 见包史表）·待用户真机安装+MigrationTest 补跑 | #1+全量绿 |
| B组后续（中继2/3） | B8/B9 导演系统+C10/C11 频率门槛 | #1+真机复验 |
| P5 群聊/P6 管理台/P7 地图 | 排队 | P4 全闭合 |

### 真机验收待决项

| 项 | 验收标准 | 来源 |
|---|---|---|
| ZD-11 复验 | **净库口径**：新日程自然生成后，主动消息中角色日程以三人称/角色主语呈现——"你在喝/你正在"式用户归因绝迹（原威士忌存量素材已失·缺陷本质=渲染人称与具体条目无关，素材随日程生成自动重建） | 三合一大考包 |
| 锚点新格式 | 两种角色锚点均含全团层前缀·航行中=海域级（锚点随对话自然重新积累） | #1+大考包 |
| 存量失声 | **素材已失（ZD-12）→口径重定义**：读料侧过滤由单测金样锁定（GateDoubleCardTest 管线级样本·不依赖真机数据仍有效）；真机考核并入"生成侧新日程"行——新双卡下跨团脏条目无法自然再生=闸门设计目标达成 | ZD-10 终验 |
| 贝拉"你在哪" | 装新包后立即答莫比迪克号（world_sync 即时失据·不再等 6h）·**净库重建前置**见下方建团 UI 定性 | B组#2 |
| 贝拉×艾斯传播 | 艾斯锚点更新→贝拉锚点不变（同上净库重建前置） | B组#1 传播边界 |
| 生成侧新日程 | 明早日程无跨团越权条目 | ZD-10 生成侧 |
| MigrationTest 53→54 | **挂账缓跑（2026-10-01 用户裁定）**：目的=**迁移链代码正确性回归**（与真实数据无关·事故后净库）；含 `migration53To54AddsFleetLayerJsonAndPreservesRows` 专案，不阻塞大考 | 惯例②修订版 |

> **建团 UI 定性（ZD-12 后首问·已闭）**：净库"创建角色卡"里建团 UI 不出现=**数据门控非构建缺失**——
> ①包内特征串内容级验证全中（同团分组/未配团提示/团键输入/加入·建团/退出该团/现有团 6 串均在 classes.dex）；
> ②设计切片一**仅编辑模式**渲染（CharacterEditScreen.kt:481 `if (viewModel.isEditing)`，isEditing=editingUuid!=null）。
> 操作路径：创建角色卡→保存→进该卡**编辑**→"世界"段下方即"同团分组"段（团键输入+加入/建团+现有团 chips）。

> **大考素材重建剧本（净库口径·2026-10-01）**——大考从"存量污染对抗考"转"净库正向考"，闸门行为不变：
> **范围口径（2026-10-01 订正）**：本大考=**P3+P2 切片联合验收**（ZD-10/ZD-11/B组#1/#2/#1锚点格式），**非 P4 全量**——P4 余量=中继2/3（B8/B9+C10/C11·规格待外部工单）+真机复验后才闭合。
> 1. **建卡**：创建考核所需角色卡（贝拉、艾斯、白团成员等）；
> 2. **建团**：各卡进编辑模式配团（待办1路径：编辑→世界段下方→同团分组→团键+加入/建团）——白团成员同键成团，贝拉/艾斯异键；
> 3. **认知**（可选加速）：跨团认知按正本五.2 在角色卡"跨团关系"栏登记，或走演出路径自然建立；
> 4. **锚点积累**：与各角色对话推进数回合——格式指导恒注入，模型输出双层锚点（全团：…（船名·MotionState））→ 落库带 fleetLayerJson → 考"锚点新格式"；
> 5. **world_sync 双考**：对话使艾斯（或白团成员）锚点更新后**立即**问贝拉用户/船位置→考 B组#2 即时失据；同一动作后查贝拉自身锚点未变→考 B组#1 传播边界；
> 6. **日程生成**：今晚零点/明早日程自然生成→考"生成侧无跨团越权"；
> 7. **余温三人称**：日程生成后触发/等待主动消息→考 ZD-11 净库口径（角色日程三人称·"你在喝"式用户归因绝迹）。
> 原存量两项处置：ZD-11 转结构性口径（上表）；存量失声由单测金样锁定+真机并入生成侧（上表）。

### 用户侧待办

- [ ] ~~健康快照备份~~ **已失效（2026-10-01 订正："数据完好"系误判·经 ZD-12 事故报告修正）**→ 改为：大考通过+素材重建完成后，以重建态为新基线做首次快照
- [ ] 生成侧首考结果回报（今晚零点/明早日程截图 + 主动消息素材·首查贝拉页）
- [ ] P2/P3 九项清单继续推进（部分已过）
- [ ] **中继2/3 规格给单**：B8/B9 导演系统与 C10/C11 频率门槛的工单规格在外部计划（见下方交棒单"待用户给单"两项）

### 下一棒交棒单·中继2/3（B8/B9 导演系统 + C10/C11 频率门槛·勘察版 2026-10-01）

**前置**：真机复验素材（明早日程页+主动消息两份·首查贝拉页）→ 绿即开工。

**B8/B9 导演系统——仓内底座已齐，等触发面规格：**
- 底座（实现就绪·零调用方）：`StoryStateRepository.applyDirectorEvent` 通道②（多卡受影响逐卡落锚+ledger 记账+星标）；
  `broadcastToFleetMates` 防环白名单含 DIRECTOR；`AnchorSource.DIRECTOR` 枚举在库；world_sync ⚓通知复用 B1 通道。
- 待用户给单：①导演事件源（手动触发/规则/定时？）②事件规格（eventKey/description 粒度与多卡选择口径）③UI 落点（独立面 or 归 P6 管理台）。
- 金样方向：多卡落锚断言+广播扇出计数+防环（world_sync 不再广播）。

**C10/C11 频率门槛——仓内零散坐标，等门槛对象规格：**
- 既有坐标：`SettingsRepository` 跨日程互动频率 0-3（ScheduleCoordinator 消费·约20%/50%/80%档）；
  `MomentGenerationService` 发帖频率/今日上限/4h 冷却/睡眠欠帖；`NotificationLearningService`+`NotificationWindowStatsDao` 通知窗学习；
  `FosterDaughterEntry` KDoc 已预留"C10 频率控制台管开关"口径。
- 待用户给单：C10/C11 的门槛对象（主动消息？新闻管道 publish/calculateDeliveryPlan？注入开关收编？）与阈值口径。

**状态对齐备注**：仓内台账 P4 A组已真收口（`3d1916c`，relations 表+A1/A3+闸门接线+A7+A2 宏断言+名→UUID）；
"下一棒"按台账余量=中继2/3。若外部计划中"A组"为新批次/重开项，以用户工单为准再对齐本表。

---

交棒完毕。HEAD=`33731c9`，工作区干净，origin/zcode-mod 同步。
