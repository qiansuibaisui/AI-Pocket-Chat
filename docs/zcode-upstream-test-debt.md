<!-- [zCODE] 新增文件：上游遗留测试失败挂账台账（基线实证 8 例·不阻塞施工·升级合并时对照核销） -->

# 上游测试债台账（zcode-mod 基线，2026-09-08 实证）

**实证方法**：`git stash` 摘除 P0-第1项全部改动后，在干净基线（commit `044f9c5`）上复跑同名测试类，以下 8 例仍失败——证明为上游遗留、与本 fork 改动无关。升级合并 SOP（fetch 上游 → merge）时逐条对照核销：上游已修则销账，未修则保留挂账。

| 编号 | 测试类 > 用例 | 失败原因（一句话） |
|---|---|---|
| ZD-1 | `story.StoryNarrativeInjectionTest` > T2_1_三关路径_弧线大纲与改前逐字节相等 | 金样比对：弧线大纲 prompt 装配输出与冻结基线逐字节不一致（上游提示词改动未同步金样） |
| ZD-2 | `story.StoryNarrativeInjectionTest` > T2_1_三关路径_首章与改前逐字节相等 | 同上（首章装配金样漂移） |
| ZD-3 | `story.StoryNarrativeInjectionTest` > T2_1_三关路径_续章与改前逐字节相等 | 同上（续章装配金样漂移） |
| ZD-4 | `toolcalling.ToolCallingPromptAssemblyGoldenTest` > marker_mode_message_structure_is_frozen | 文本标记模式消息结构金样失配（上游装配结构变更未过冻结测试） |
| ZD-5 | `toolcalling.ToolCallingPromptAssemblyGoldenTest` > marker_mode_assembly_freezes_all_marker_segments | 同上（标记模式分段冻结断言失配） |
| ZD-6 | `toolcalling.ToolCallingPromptAssemblyGoldenTest` > tool_mode_message_structure_is_frozen | 工具模式消息结构金样失配（同上游漂移） |
| ZD-7 | `toolcalling.ToolCallingPromptAssemblyGoldenTest` > tool_mode_assembly_freezes_tool_segments_and_drops_marker_howto | 同上（工具模式分段 + 剥标记说明断言失配） |
| ZD-8 | `toolcalling.ToolCallingPromptAssemblyGoldenTest` > promise_rule_onlyInMarkerMode_andNeverInVoiceCall | promise 规则段注入位置/条件断言与当前装配不符（上游 [promise] 规则改动后未更新该冻结用例） |

**性质判定**：两类均为「冻结金样」型测试（防提示词意外漂移的看门狗），失败含义 = 上游某次提示词/装配调整后金样未同步再冻结。不阻塞 zcode 任何施工；但**升级合并后若仍失败且上游无对应修复，须核对是否为合并冲突的信号**。

**另记（环境性 1 例，非上游债）**：`ui.story.StoryShareCardRendererTest` > 落盘_写png返回FileProvider_uri —— 本机 Robolectric × FileProvider 根匹配失败（`Failed to find configured root`，摘 `.mod` 后缀对照仍失败 → 与 fork 改动无关，属本机测试环境问题；作者机全绿记录在案）。

---

**环境例第 2 例（2026-09-20·点3 全量轮实证）**：`ui.liuli.chat.sheets.LiuliStickerPickerSheetTest` > tab0空态文案且没有添加钮 —— 全量并发下 Robolectric Compose 空态断言偶发不显示（顺序污染型）；**隔离复跑该类 BUILD SUCCESSFUL**，且点3 改动零触碰贴纸/Compose 链（diff 无交集）→ 定性环境性非回归。与 FileProvider 环境例同挂：全量失败预期口径 = ZD-1~8 + 环境 2 例 = 11。
