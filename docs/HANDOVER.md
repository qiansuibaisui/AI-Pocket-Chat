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
| ZD-13 | P1 归因 | 主动消息**由头指称错绑**：排程侧锁定前缀「TA 的日程：」到点侧进入「你是{角色}，给{user}发一条消息」框架后，「TA」被模型绑到**收件人**→角色把自己日程的行为归因给 user（真机实锤 2026-10-04：艾斯把自己日程的宴席吃喝反咬成"刚才看着你说得那么香，怎么不叫我一起"）。ZD-11 二人称全量扫描扫不到它——本缺陷是**三人称代词在第二人称消息框架里错绑**，非二人称渲染 | `2093485`：由头前缀改角色自指「你自己的日程：」（框架内「你」恒=角色）+composer 侧旧闹钟由头归一化 normalizeOccasionDeixis（升级即愈·88e4b25 与 0a5e59d 两包均带缺陷）+行为金样 4 例（断言成品提示词中可错绑字样绝迹） | ✅ 全链闭合（诊断→修复→61 绿→台账→**图纸 §3.1 改字已批准**·2026-10-04 业主终审侧在案）·待 push+随下包真机复验 |

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
4a6e36c  中继2（B8/B9导演系统+金样19例·拍板四点全落码）
dcce023  中继2收货观察②（B8 cast镜像改⚓信封——纯文本被消费端整条跳过）
0a5e59d  中继3（C10控制面+fanOut跨团扩面+压级知情名单+发射闸/认知闸·六项全落）
2093485  ZD-13修复（由头「TA 的日程」指称错绑→自指前缀+旧闹钟归一化+行为金样4例）
3fb26ec  P6切片一（导演控制台：三段面板+双脸入口+确认弹窗+写入留痕+同worker扫描+P5预埋①枚举位）
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
| 中继2/3 验收包（四合包） | 0a5e59d（docs cbd7d32 不入包） | Oct 3 | 225,959,001 | 文件 `8882371d624a37d46961272055adf3d36327bcdc65f15db397b758515ed6311d`·证书同前（`922c5fe7…ae55`·v2）·包内已验中继2/3 特征串（news_control_triggers/导演文游/dir-r:/陌生卡不得发起）·零 schema 变更（v54→v54 升级装保留数据）·**未装机即被五合包取代** |
| **五合包（ZD-13+中继2/3）** | c707e3c（代码 2093485·含四合全部内容） | Oct 4 | 225,959,001 | 文件 `77268ece320a3bfdf43a4a43e3853dff8576dc5c92f7334901fadf6e54486de2`·证书同前（`922c5fe7…ae55`·v2）·与四合包同字节大小不同 SHA（内容异·构建 14m3s 增量）·包内特征串全中：ZD-13（你自己的日程·`TA 的日程`仅存于 normalizeOccasionDeixis 匹配字面量=治愈旧闹钟故留）+中继2/3 四串+锚点#1（全团：）·零 schema（v54→v54）·**装机口径=跳过四合包直接装本包**（业主询议拍板 2026-10-04：归因前提已满足——88e4b25 素材已取完判读毕；ZD-13 与中继2/3 故障域不相交；B9 多日观察期不带已知归因缺陷跑） |

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
| push 验证 | 一律 `git ls-remote origin zcode-mod`（指名分支·不用裸 HEAD），远端 hash 为唯一判据（本地报错≠远端未达——f05bc0b 案例：本地 connect 失败但远端已收到） | ZD-12 后 2026-10-01 用户裁定 |

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
| ZD-13 由头自指 | `notification/ProactiveOccasionText.kt` | occasionForEvent 前缀「你自己的日程：」 |
| ZD-13 旧由头归一化 | `prompt/notification/ProactiveMessageComposer.kt` | normalizeOccasionDeixis（composeUserPrompt 内调用） |
| P6 导演控制台 VM | `ui/settings/DirectorConsoleViewModel.kt` | confirmPending 唯一写入口+DirectorConsoleAudit |
| P6 导演控制台 UI | `ui/settings/DirectorConsoleScreen.kt` | 三段+确认弹窗（=审计三元组预览） |
| P6 控制台接线 | `ui/AIChatApp.kt` | composable("directorConsole")+SettingsScreen 双脸入口 |
| P6 最近导演事件 | `data/local/dao/StoryStateDao.kt` | recentDirectorLedger |
| 防复读 | `prompt/AnchorInjectionBuilder.kt` | 【防复读】段 |

## ⑤ 待办与观察项

### 待施工

| 项 | 量级 | 前置 |
|---|---|---|
| **#1 锚点格式双层规范化** | ✅ 施工闭合（`88e4b25`）·待真机验收 | 全量回归已过（下行） |
| 全量回归（#1 后） | ✅ 一轮已跑（2026-10-01·Gradle 官方计数）：9244 例·失败 10·**全部存量红**（干净 HEAD 复跑同 10 例：StoryNarrativeInjectionTest 3 + ToolCallingPromptAssemblyGoldenTest 5 + OurDaysViewModelTest 1 + StoryShareCardRendererTest 1——环境性/基线陈旧·#1 新增 0 失败） | — |
| **三合一大考包出包** | ✅ 出包完成（2026-10-01·双报SHA 见包史表）·待用户真机安装+MigrationTest 补跑 | #1+全量绿 |
| B组后续（中继2/3） | **中继2 ✅ 闭合**；**中继3 ✅ 施工闭合（2026-10-02·六项全落：C10 控制面/fanOut 跨团扩面/压级+知情名单/发射闸+认知闸/项6——勘察回单 v2 补笔含施工记录）·待验收** | #1+真机复验 |
| P5 群聊/P7 地图 | 排队；**P5 预埋账四条已入册**（会话抽象纪律/B8 群聊三问/记忆互通=架构纪律非愿望/在场口径待定案——见 `docs/P6勘察单-管理台首切片.md` §五） | P4 全闭合 |
| **P6 管理台切片一（导演控制台）** | **施工闭合+全量回归已过**（`3fb26ec`·2026-10-04·五拍板全默认案+留痕采纳——裁定与施工记录见 P6 勘察单 §五/§六/§七：三段面板+双脸入口+确认弹窗+写入留痕+金样 12+8；两轮全量 9299 例稳定红 9=基线存量·零新增确定性失败·游走红跨测污染挂账观察）·待真机 | 勘察单已裁 |
| **下包时机** | ~~与 P6 首切片捆绑~~ → **五合包已提前出**（2026-10-04 业主询议拍板"现在出"：四合包未装即取代·ZD-13 复验随本包·见包史）；后续按切片进度出包 | ZD-13 |

### 真机验收待决项

| 项 | 验收标准 | 来源 |
|---|---|---|
| ZD-11 复验 | **2026-10-04 真机复验：归因反转仍现→挖出 ZD-13（见缺陷史）**。ZD-11 本体（余温渲染二人称）无复发迹象；"你在喝/你正在"式归因的净库口径**并入 ZD-13 一并复验**（须下一包·两包均带 ZD-13） | 三合一大考包 |
| ZD-13 复验 | 装下一包后：主动消息中角色日程以自指/角色主语呈现——"TA 的日程"式收件人归因绝迹；升级后已烤旧由头经 composer 归一化即愈（2h 保质窗内旧闹钟由头本就短寿，归一化为双保险） | ZD-13 |
| 锚点新格式 | **2026-10-04 真机初验绿**：艾斯/贝拉回复均含全团层前缀（白胡子海贼团·新世界·某海域（莫比迪克号·航行中）——航行中退海域级正确）；贝克曼全团层+电话虫定位落位成功 | #1+大考包 |
| 无团角色锚点路径 | **2026-10-04 真机首考绿（四合包测试清单观察项）**：卡莲（不入团）锚点仅个人层（新世界·琉璃岛·店门口级）+在场+节点，无全团段、不崩——无金样路径首过 | 测试清单 v2 |
| 存量失声 | **素材已失（ZD-12）→口径重定义**：读料侧过滤由单测金样锁定（GateDoubleCardTest 管线级样本·不依赖真机数据仍有效）；真机考核并入"生成侧新日程"行——新双卡下跨团脏条目无法自然再生=闸门设计目标达成 | ZD-10 终验 |
| 贝拉"你在哪" | 装新包后立即答莫比迪克号（world_sync 即时失据·不再等 6h）·**净库重建前置**见下方建团 UI 定性 | B组#2 |
| 贝拉×艾斯传播 | 艾斯锚点更新→贝拉锚点不变（同上净库重建前置） | B组#1 传播边界 |
| 生成侧新日程 | 明早日程无跨团越权条目 | ZD-10 生成侧 |
| MigrationTest 53→54 | **挂账缓跑（2026-10-01 用户裁定）**：目的=**迁移链代码正确性回归**（与真实数据无关·事故后净库）；含 `migration53To54AddsFleetLayerJsonAndPreservesRows` 专案，不阻塞大考 | 惯例②修订版 |
| 背景板事件卡不触发主动回应 | 收货观察②：B8 背景板 cast 侧镜像卡插入后，该角色**不得**自发主动回应——真机补看。代码层预答：镜像走 ⚓ 同款 SystemEventJson 信封（`4a6e36c` 后修复——纯文本会被消费端整条跳过，UI/LLM 双落空）；无任何管线按消息插入自动回帖（未答恢复=朋友圈域） | 中继2 |
| 无关角色只知大概 | 对单问三衍化：被问及重大事件时，无关角色只知报道级摘要（"听说…"）不知细节——细节追问应指向当事人/转述链。代码层现状=三级衰减已落（详版80字/概述30字/模糊），同团详版是否越界待终审裁 | 对单回单 2026-10-02 |

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
> 8. **B9 远方相遇验收前置（收货观察①）**：fail-closed 24h——纯电话虫期 B9 全程静默（代理失效）。前置=**先攒至少一次线下会话**
>    （OFFLINE_MEETING 记账行≤24h）；OVERRIDE 兜底=改 `DirectorRulesConfig.USER_FLEET_KEY_OVERRIDE` 常量重出包（本批参数位形态·C10 收编后改读设置）。
> 原存量两项处置：ZD-11 转结构性口径（上表）；存量失声由单测金样锁定+真机并入生成侧（上表）。

> **四合包真机测试清单**：归档 `docs/四合包测试清单.md`（v2·2026-10-03）——班底=艾斯（白团）+贝克曼（`redhair` 跨团）+卡莲（琉璃岛酒馆老板娘·原创·不入团），B9 相遇对=**贝克曼×卡莲**（白团卡与用户代理恒"在场"=结构性排除）；**定位对话须走电话虫**（线下见面会把用户代理记到琉璃岛→对被在场守卫排除）；素材判读+装包+八步观察全表见该文件。

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

### B8/B9 勘察单（2026-10-01·终审工单已到·提案待拍板后写码）

**勘察结论（底座四项全确认）**：①`StoryEventSource.DIRECTOR` 枚举在库且已入 `SCHEDULE_STAR_SOURCES`（日程星标✓）；
②`applyDirectorEvent` 通道②就绪且**本身不调广播**（广播是独立的 `broadcastToFleetMates` 调用——B9"两人相遇≠全船移动"恰好不该广播，佩罗娜教训同源）；③新闻管线 `publish` 幂等（sourceRaw+sourceRefUuid）+延迟三态扇出（同团 6h/跨海 24h/无风带阻断）="择机报道"现成通道；④周期基建=WorkManager PeriodicWork+BootReceiver 补排（NotificationAlarmScheduler 同范式，B9 扫描直接挂）。

**提案 1·eventKey 粒度**（沿既有惯例 kebab+id：`offline-meeting-$sessionId`/`news-$uuid`/`schedule_event_$id`）：
- B8 手动档：`dir-m:{sessionId}:{turnIdx}`——**每回合一记**。【已执行事件】注入按清单引用，回合粒度防复读精确到节拍；唯一索引天然幂等（重试零重复）。
- B9 规则档：`dir-r:{pairKey}:{yyyyMMdd}`（pairKey=两角色 uuid 排序拼接）——**每对每日一记**，唯一索引即"每对每日≤1"硬闸；72h 冷却另走 recentLedgerFor 前缀扫描。

**提案 2·B9 频率参数（数字提案·参数位形态=DirectorRulesConfig 常量对象，C10 收编时改读设置）**：
| 参数 | 提案值 | 依据 |
|---|---|---|
| 扫描周期 | 6h | 对齐锚点 FRESH 上限（位置硬约束时效=扫描窗，语义自洽） |
| 触发概率 | 10%/合格对/次 | 期望≈每对每日 0.4 次——远方事件该稀 |
| 每日上限 | 全库 2 条/日 | 轻量事件不刷屏 |
| 单对冷却 | 72h | recentLedgerFor 30 天窗内查 `dir-r:{pairKey}` 前缀 |
| 择机报道 | B9 30% 概率 publish；B8 手动档默认不报（用户亲历无需新闻） | 远方小事非新闻常态 |

**提案 3·B9 接线**：扫描器（WorkManager 6h）→ 判定合格对（同位置+{{user}}不在场，见提案4）→ 概率/上限/冷却/幂等四闸 → `applyDirectorEvent`（双方落锚+ledger 记账+星标）→ 30% 择机 `newsPipeline.publish(sourceRaw="director_rules", sourceRefUuid=eventKey, participants=[双方])`。**不走 broadcast/⚓通知**（相遇是两人事）；B8 仅事件含船位变更时才补调 broadcast。

**提案 4·知情记账数据面（不新建认知结构，全复用四源判定）**：
- 参与者=ledger 行（DIRECTOR）→ 闸门四源自动认 ③新识（可提及共同经历）✓
- 不在场角色=**零写入**，认知经 news 延迟扇出或对话转报建立（认知边界四.2 既有口径）✓
- {{user}}：B8 入场/背景板=文游 prose 落**参与角色的 conversation**（线下模式同范式·在场亲见由上下文自证，背景板=亲见未参与）；B9=**{{user}} 侧零写入**，获知唯一通道=摩根斯推送/转报 ✓
- **{{user}} 在场判定（拍板点）**：提案=「最近一次对话角色所属船团的当前锚点」为用户位置代理（养女条目叙事上用户随团）+`userFleetKeyOverride` 参数位；B9 候选对须两角色锚点均不与代理位置同位置（同位置口径=fleetLayer.seaArea+MotionState 一致，双层锚点 absent 时回退 normalizeIslandTop）。

**待终审拍板四点**：A eventKey 两案；B 频率五数字；C {{user}} 在场判定代理案；D B8 prose 落参与角色 conversation+B9 不走广播的接线确认。

**拍板结果（2026-10-01 终审·全部落码）**：
- A ✅ 照准（dir-m 回合粒度/dir-r 对日粒度·唯一索引硬闸）；
- B ✅ 五数全批（DirectorRulesConfig 常量形态·C10 收编改读设置）；
- C ⚠️ 修正后准——**代理只认线下/见面类会话**（电话虫等异地通话不计入，否则代理漂到对方船上双向误判）：落法=代理唯一取材口 `latestOfflineMeetingLedger`（DAO source='offline_meeting' 过滤·结构性排除异地通话）+ `USER_FLEET_KEY_OVERRIDE` 兜底；代理超 24h 失效→整轮 fail-closed 放弃；
- D ✅ 照准+补——**背景板 prose 双侧落**：host 会话（=「亲见」界面证据·用户开线下模式看着剧情推进）assistant 消息 + 其余 cast 会话 system 事件卡镜像；B9 不走广播/⚓照准（B8 仅船位变更补调 broadcast）。

**中继2 施工记录（B8/B9·管道无 UI·UI 归 P6）**：
- `director/DirectorRulesConfig.kt`（五参数位+代理时效+override）；
- `director/DirectorRulesScanService.kt`（B9：同位置双层口径→跨团配对→在场排除→四闸→通道②记账→30% 择机 publish participants=双方；{{user}} 侧零写入=结构性（不注入消息仓储）；模板句零 LLM 成本）；
- `director/DirectorTextGameService.kt`（B8：回合级无状态·记账即时落库进程死亡不丢账；入场/背景板模式指令差；被点名 cast 通道②记账 dir-m:{sid}:{turn}；fleetLineOf 复用 #1 粒度口径）；
- `director/DirectorRulesScanWorker.kt`+AppViewModel 排程（6h 周期·KEEP·requireNetwork=false·WorkManager 自持跨重启）；
- StoryStateDao 三查询（最近线下行/当日计数/对冷却）·零 schema 变更零迁移；
- LogSource 两来源（导演文游/导演规则扫描）入 WORLD 类目（LogCategory 不变量测试同步覆盖）；
- 金样 19 例全绿：B9 13 例（同位置双层/回退岛名/UNKNOWN·室内·途中拒配/fail-closed/在场排除/C 修正结构性证据/四闸各一/跨团排除/防广播/日期桶锁定）+B8 6 例（双侧落点+回合记账/背景板旁观指令/入场输入/船位变更才广播·不变不播/空响应零写入）。
**施工切面（拍板后）**：DirectorRulesConfig+DirectorRulesScanService+WorkManager/Boot 挂点 → DirectorTextGameService（start 入场/背景板·advance·finalize）→ 金样（B9 判定纯函数四闸+记账三态知情映射+防广播误触）。红线缺料上报：C/D 两处不拍板不写码。

---

交棒完毕。HEAD=`33731c9`，工作区干净，origin/zcode-mod 同步。
