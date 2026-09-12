# Handoff: 在历史记录中增加"查看分区列表"功能

> 生成时间：2026-08-30
> 分支：`bugfix/view-patition`
> 关联上下文：本项目已具备 (1) OTA 查询历史记录；(2) 独立的在线 OTA 包分区列表解析/查看页（payload 页）。本任务要把两者打通——在查询历史里能直接看到某次查询结果对应的分区列表。

---

## 1. 用户诉求

"在查询历史中，增加查看分区列表的功能" —— 即在 `HistoryCard` 的历史项上，提供一个入口，让用户能查看该次 OTA 查询结果对应的分区（partition）列表，而不必重新走"解析 URL → payload 页"的完整流程（或使其更顺手）。

---

## 2. 现状盘点（已探明，可直接复用）

### 2.1 历史记录（VivoOtaViewModel）
- 数据类：`app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoModels.kt` 的 `QueryHistoryEntry`
  ```kotlin
  data class QueryHistoryEntry(
      val timestamp: Long,
      val model: String,
      val codename: String,
      val swVersion: String,
      val resultVersion: String,
      val fileSize: String,
      val downloadUrl: String,   // ← OTA 包 URL，分区解析的关键输入
      val channel: String = "NORMAL"
  )
  ```
- 存储：`SharedPreferences("vivo_ota", ...)` 的 `history` 键，JSONArray 序列化；内存态 `_uiState.value.history` 最多 20 条，`HistoryCard` 展示 `take(10)`。
- 写入点：`VivoOtaViewModel.addToHistory(...)`（约 line 276），由 `fetchChangelog`/`query` 成功路径调用。
- 历史项当前交互（`VivoApp.kt` `SwipeToDeleteHistoryEntry`）：点击下载链接复制；左滑删除。**没有"查看分区列表"入口**。

### 2.2 分区列表解析（VivoPayloadViewModel / VivoPayloadUtil）
- 解析入口：`VivoPayloadViewModel.parseFromUrl(url: String)`（约 line 85）与 `parsePayload(url)`（line 139）。二者核心逻辑一致：
  1. `VivoPayloadHttpUtil.init(target)`（HTTP 范围读取，单游标单例，需 Mutex 串行化）
  2. `PayloadUtil.getPayloadOffset(target)` 定位 payload.bin
  3. `PayloadUtil.initPayload(fileName, httpUtil, offset)` → 得到 `Payload`，含 `deltaArchiveManifest.partitionsList`
  4. `PayloadUtil.getPartitionInfoList(payload)` → `List<PartitionInfo>`
- 分区数据类：`app/android/src/main/kotlin/io/github/long36708/updater/vivo/payload/VivoPayloadModels.kt` 的 `PartitionInfo`（`partitionName`, `size`, `rawSize`, `sha256`, `operationsCount`, ...）。
- 展示 UI：`VivoPayloadDumperScreen.kt` 的 `PartitionItem` / 分区列表 LazyColumn（line ~329），以及 `PartitionDetailDialog`。
- 触发位置：`VivoApp.kt` line 205 `payloadViewModel.parseFromUrl(dumpUrl); showPayloadDumper = true` —— 说明已有"从 OTA 结果 URL 直接带入并打开 payload 页"的模式，可照搬。

### 2.3 关键约束
- `VivoPayloadHttpUtil` 是单例、单 position 游标；多处并发解析必须加 Mutex 串行（现有 `zipMutex`/`payload` 解析已这么处理，新增入口要复用或新增锁）。
- 分区名只来自 payload.bin 的 `DeltaArchiveManifest`，不在 OTA 查询结果里，也不在历史项里 —— **历史项当前不持有任何分区信息**。
- targetSdk=37，仅 `INTERNET` 权限；分区解析是 HTTP 读（不落盘、不写存储），权限无碍。
- 已知未解问题（与本任务无关，勿被带偏）：重编译 debug APK 下 `cryptoReady=false` 会导致 `query()` 静默不返回更新包，属 `VivoOtaViewModel` 的签名校验，非本任务范围。

---

## 3. 推荐实现方案（方案 B：点击时联网解析，复用现有能力）

**理由**：改动最小、复用 `parseFromUrl` + `VivoPayloadDumperScreen`，不改动历史存储结构（不破坏旧版本历史 JSON 兼容），且分区列表随 OTA 包更新保持实时准确。

### 3.1 交互
在 `SwipeToDeleteHistoryEntry`（或新增一行操作按钮）里，当 `entry.downloadUrl.isNotEmpty()` 时，增加一个"分区列表"入口（文字按钮，复用之前定稿的紧凑圆角文字按钮样式：`Box + clip(RoundedCornerShape(8.dp)) + background(primary) + padding(horizontal=14.dp, vertical=7.dp) + Text("分区列表", 13.sp, White)`）。

点击行为：
```kotlin
payloadViewModel.parseFromUrl(entry.downloadUrl)
showPayloadDumper = true   // 与 line 205 完全一致
```
即跳转/展开已有的 payload 页，复用分区列表与详情、提取能力。

### 3.2 需要联通的地方
- `VivoApp.kt` 的查询页已持有 `payloadViewModel`（见 line 205），历史项在同一 `@Composable` 作用域，可直接复用，无需新建 ViewModel 实例。
- 注意 `parseFromUrl` 与历史项解析、zip 浏览可能并发触发 `VivoPayloadHttpUtil` 单例；若用户在 payload 页正在操作时点历史项解析，需确认 `VivoPayloadViewModel` 内部已对 HTTP 访问加锁（目前 `parseFromUrl` 在 `viewModelScope.launch` 里同步跑，`VivoPayloadHttpUtil` 非线程安全）。**建议**：在 `VivoPayloadViewModel` 给 `parseFromUrl`/`parsePayload` 包一层 `Mutex`，避免与 zip 浏览（`zipMutex`）冲突。可复用同把锁或新增 `parseMutex`。

### 3.3 可选增强（不在本次必需范围）
- 在历史项上展示"分区数 N"摘要：需在保存历史时解析一次分区名（见方案 A），或点击解析后回写。若只做方案 B，历史项仅显示按钮，不显示数量。

---

## 4. 备选方案 A（离线缓存分区名，保存时解析）

若用户更想要"离线、即时"的体验（不每次联网）：

1. `QueryHistoryEntry` 增加字段 `partitionNames: List<String> = emptyList()`（仅名字，体积小；如需大小/sha 可加 `partitionSummary: String`）。
2. 修改 `saveHistory`/`loadHistory` 的 JSON 读写（SharedPreferences），注意旧历史项无该字段要兜底 `optJSONArray` 空列表。
3. 在 `addToHistory` 成功写入后，异步用 `VivoPayloadUtil.initPayload` 解析 `downloadUrl` 的分区名列表，再 `update` 回对应 `timestamp` 的历史项并 `saveHistory`。解析失败（如 URL 失效）则留空，不影响历史本身。
4. 历史项 UI 直接展示 `entry.partitionNames`（折叠展开，复用 `PartitionItem` 的轻量展示或纯文本列表）。

**取舍**：A 体验好但改动大（存储结构 + 异步回写 + 兼容旧数据），且 OTA 查询流程会变慢（多一次 payload 解析）。B 更简单稳妥。**默认按 B 实现，除非用户明确要离线**。

---

## 5. 实施 Checklist（方案 B）

- [ ] 在 `VivoApp.kt` `SwipeToDeleteHistoryEntry`（或 `HistoryEntrySelectionRow`）中，对 `entry.downloadUrl.isNotEmpty()` 增加"分区列表"紧凑文字按钮。
- [ ] 按钮 `onClick`：`payloadViewModel.parseFromUrl(entry.downloadUrl); showPayloadDumper = true`（确认 `payloadViewModel`/`showPayloadDumper` 在该作用域可见；若不可见，按 line 205 模式上提状态）。
- [ ] `VivoPayloadViewModel`：给 `parseFromUrl`/`parsePayload` 内 HTTP 访问加 `Mutex`，与 zip 浏览串行，避免 `VivoPayloadHttpUtil` 单例并发冲突。
- [ ] 字符串：在 `strings.xml` 与 `values-zh-rCN/strings.xml` 增加 `history_partitions`（中："分区列表"，英："Partitions"）。
- [ ] 若需显示分区数量摘要，可临时在解析后通过 `payloadViewModel.uiState.partitions.size` 回显（可选）。
- [ ] 编译：`.\gradlew.bat :app:android:assembleDebug --no-configuration-cache`。
- [ ] 自测：历史项点"分区列表"→ 正确打开 payload 页并展示该 URL 的分区列表；与手动解析行为一致。

---

## 6. 关键文件清单

| 文件 | 作用 |
|------|------|
| `app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoModels.kt` | `QueryHistoryEntry` 数据类（方案 A 需改） |
| `app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoOtaViewModel.kt` | 历史增删/存储；`addToHistory` (line 276)、`loadHistory`/`saveHistory` (line 302/329)、`deleteHistoryEntry` (line 163) |
| `app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoApp.kt` | `HistoryCard`(line 674)、`SwipeToDeleteHistoryEntry`(line 818)、payload 页触发 (line 205) |
| `app/android/src/main/kotlin/io/github/long36708/updater/vivo/payload/VivoPayloadViewModel.kt` | `parseFromUrl`(line 85)/`parsePayload`(line 139)、`currentPayload`、`zipMutex` |
| `app/android/src/main/kotlin/io/github/long36708/updater/vivo/payload/VivoPayloadUtil.kt` | `initPayload`(line 59)、`getPartitionInfoList`(line 96)、`getPayloadOffset` |
| `app/android/src/main/kotlin/io/github/long36708/updater/vivo/payload/VivoPayloadModels.kt` | `PartitionInfo`、`Payload` 数据类 |
| `app/android/src/main/kotlin/io/github/long36708/updater/vivo/payload/VivoPayloadDumperScreen.kt` | 分区列表 UI（`PartitionItem` line 396、`PartitionDetailDialog`），可复用样式参考 |

---

## 7. 注意事项 / 坑

- **不要**把历史存储结构（`QueryHistoryEntry`）随意加字段破坏旧 JSON；若走方案 A，读路径用 `optJSONArray` 兜底。
- **不要**在 `VivoOtaViewModel` 里直接 new 一个 `VivoPayloadViewModel`；两者生命周期不同，应复用 `VivoApp.kt` 中已有的 `payloadViewModel` 实例。
- `VivoPayloadHttpUtil` 单例非线程安全，新增并发解析入口务必加 Mutex。
- OTA 包体积 4.3–11.6 GB，但分区解析只 HTTP 范围读取 payload.bin 的 metadata 段（几 MB 级），不会下载整包，速度可接受。
- 与历史相关的"查询不到更新包"旧问题（cryptoReady）不属于本任务，排查时勿混淆。
