<!-- [zCODE] 新增文件：P0-第0项交付文档——原版→改版数据迁移指引（只写文档不写代码） -->

# 原版→改版数据迁移指引

适用场景：从作者版（`com.situ.aichat`）迁移到 zcode 改版（`com.situ.aichat.mod`，debug/release 均带 `.mod` 后缀）。两版包名不同，**可同时安装、互不干扰**，因此严格意义上无需卸载原版——数据通过「原版导出 → 改版导入」复制，原版可留作回退保险，确认无误后再删不迟。

## 一、原版 APP 内导出

1. 原版内进入 **设置 → 备份与恢复**（入口文案 `backup_title`，见 `app/src/main/res/values-zh-rCN/strings.xml:588`；界面实现 `app/src/main/java/com/situ/aichat/ui/backup/BackupScreen.kt`）。
2. 保持「**包含聊天图片和语音**」开关为开（媒体不随库走，关掉就没了）。
3. 点「**导出全部角色**」→ 系统文件选择器（SAF CreateDocument）自选保存位置与文件名，得到一个 **zip 备份**（自动备份的命名格式为 `AIChat_backup_<yyyyMMdd_HHmmss>[_media].zip`，见 `app/src/main/java/com/situ/aichat/work/AutoBackupFolder.kt:46`）。
4. 建议再复制一份到电脑或网盘留档（zip 内含 manifest 版本号与导出时间，`data/backup/BackupExporter.kt:139-144`）。

> 若原版开启过「定时自动备份」，也可直接用其备份目录里最新一份 `*_media.zip`。

## 二、新 APP（改版）导入

1. 安装 `app-release.apk`（`.mod` 包名，自建 keystore 签名）。
2. 进入 **设置 → 备份与恢复 → 导入备份**，选中原版导出的 zip。
3. 进入**导入预览**：逐角色显示消息数，以及「将导入为新角色 / 本地已存在该角色」判定；新装改版为空库，全部显示为新角色。
4. 逐角色选择策略（**覆盖已有 / 创建副本 / 跳过**，`strings.xml:607-609`）——空库首次导入直接全量导入即可。
5. 等待「导入完成：N 个角色 · M 条消息」提示；若提示 N 条媒体恢复失败，重导一次或检查 zip 完整性。

## 三、哪些数据**不**随备份走（需手动处理）

| 项目 | 原因 | 迁移动作 |
|---|---|---|
| **API 配置与 API Key（重点）** | 备份模块（`data/backup/` 全目录）不收集 `ApiConfigEntity` 与 `ApiKeyStore` 任何数据；且 Key 用 EncryptedSharedPreferences + Android Keystore 加密（`security/ApiKeyStore.kt:24-39`），Keystore 密钥**不可导出、按安装实例绑定**——即使拷走加密文件，新安装也解不开 | 改版内 **设置 → API 配置** 重新录入 base URL、API Key、模型名；支持扫码导入（原版若有 API 配置二维码可复用） |
| 自动备份目录授权 | SAF 目录授权（treeUri grant）按应用实例发放 | 改版内重新选择一次自动备份目录 |
| 通知、麦克风、相机等系统权限 | 系统权限按包名+签名实例授予 | 首次使用对应功能时重新授权 |
| 桌面小组件 | 小组件绑定按应用实例注册 | 长按桌面重新添加 |

## 四、验收清单

导入完成后逐项核对：

- [ ] **角色**：数量与原版一致，头像、人设、开场白正常；
- [ ] **聊天记录**：抽查 2-3 个角色，消息条数与原版一致、上下文连贯；
- [ ] **提示词模块**：角色级提示词模块与全局提示词设置恢复（导出含 `characterPromptModulesJSON` 与 AppSettings，`BackupExporter.kt:112,156`；导入侧 `BackupImporter.kt:170 applyPromptModuleOverrides` 回放）；
- [ ] **世界书**：世界书条目齐全（全局段 `collectWorldBooks`，`BackupExporter.kt:127`）；
- [ ] 朋友圈、日记、故事、礼物/红包账本、钱包余额、见面回忆/承诺（抽查一两处即可）；
- [ ] 媒体：聊天内图片/语音可正常显示播放；
- [ ] **API 配置重录后发一条测试消息**，确认对话链路正常。

## 五、后续升级纪律（对应 v3 需求文档第 0 项）

- 改版发布一律用同一把自建 keystore 签名（keystore 与 `keystore.properties` 均在仓库外，绝不入库），此后**覆盖安装升级、数据无损**；
- keystore 丢失 = 无法覆盖升级（只能导出→重装→导入），请对 `D:\Android\keystores\` 目录另行备份；
- 备份 zip 格式（manifest version 2 + legacy JSON 兼容，`BackupImporter.kt:454 importLegacyJson`）是原版↔改版互迁的唯一通道，改版任何 schema 变更必须同步维护导出/导入映射（`BackupExportMappers.kt` / `BackupImportMappers.kt`），遵守 migration 纪律。
