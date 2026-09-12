# Payload 解析经验与踩坑记录（Payload Parsing Notes）

本文档记录 VIVO-OTA-Tracker-Android 解析 vivo OTA `payload.zip` 相关功能的实现要点、
已修复的 bug 与实测结论，供后续维护参考。

## 1. 解析流程概览

```
VivoPayloadHttpUtil.init(url)         // HTTP 流式读取，支持 Range
  → PayloadUtil.getPayloadOffset(url) // 在 zip 里定位 payload.bin 偏移
  → PayloadUtil.initPayload(...)      // 读取 CrAU 头 + DeltaArchiveManifest protobuf
  → PayloadUtil.getPartitionInfoList(payload) // 转成 PartitionInfo 列表
```

- `CrAU` 魔数 = `43 72 41 55`，payload.bin 以此为起点。
- 解析层与 UI 层通过 `PayloadDumperUiState` + `PayloadToast` 通信。

## 2. OTA 包类型与错误识别

vivo OTA 链接大致分两类，必须用不同方式处理：

| 类型 | 特征 | 本工具处理 |
|------|------|-----------|
| A/B 增量包 | zip 内含 `payload.bin`（CrAU 头） | ✅ 支持，解析分区列表 |
| recovery 全量包（非 A/B） | zip 内含大量 img 文件、无 `payload.bin`；多为 ZIP64 + DEFLATE | ❌ 仅给出友好错误提示 |

实测链接（2024-11 recovery）：`sysuptxdl.vivo.com.cn/.../20241104...zip`
经 Python 离线分析确认：
- 是 ZIP64 格式（central directory 用 `0xFFFFFFFF` 标记偏移）。
- 221 个条目，全为 DEFLATE 压缩的 img，无 `payload.bin`。
- 因此解析层在 `locateCentralDirectory` 找不到 payload.bin 时抛
  `IOException("NOT_A_PAYLOAD_ZIP")`，UI 层拦截并提示
  "这不是 A/B 增量包（payload.bin 不存在）。该 OTA 包可能是 recovery 全量包，无法用此工具解析。"

**错误标记约定**（解析层抛特定文案，ViewModel 用 `mapErrorMessage` 转中文）：
- `NOT_A_PAYLOAD_ZIP` → recovery 全量包 / 无 payload.bin。
- `NOT_A_VALID_ZIP` → 文件不是有效 zip（下载不完整、链接错误等）。

> 决策：用户选择**只做错误提示，不实现 recovery 包解析**（避免引入完整 recovery 解包逻辑）。

## 3. 已修复的 bug

### Bug 1：ZIP local file header 偏移算错（52747 vs 真实 61）
- **文件**：`VivoPayloadUtil.kt` 的 `locateLocalFileOffset`。
- **原因**：`byteBuffer.getLong()` 读完 LOCSIG 后 position 已前进 4 字节，
  紧接 `byteBuffer.position(byteBuffer.position() + 26)` 会得到绝对偏移 30，
  但 `fileNameLength` 字段实际在**绝对偏移 26**（即相对还需 +22 而非 +26）。
- **修复**：`+ 26` → `+ 22`。实测真实 `dataOffset = 61`（stored 方式，method=0）。

### Bug 2：proto 必填字段缺失导致解析失败
- **文件**：`update_metadata.proto` 的 `InstallOperation`。
- **原因**：部分 VABC 操作的 `type` 字段不存在，原 `required Type type = 1`
  在 AOSP 新 schema 下会报 "missing required fields"。
- **修复**：改为 `optional Type type = 1`；`getPartitionInfoList` 用
  `it.type.name` 兜底（无 type 时取枚举默认名）。

### Bug 3：输入框链接不生效（点了"解析"却用旧地址解析）
- **现象**：在输入框粘贴新的 recovery 链接、点击"解析"，界面仍按初始带入的旧地址
  解析成功，既不报错也无红色卡片。
- **根因**：`VivoPayloadViewModel.submitUrl()` 只递增了 `parseRequestId`，
  没有把输入框内容写入解析目标 `pathOrUrl`；而
  `VivoPayloadDumperScreen` 的 `LaunchedEffect(parseRequestId)` 里调用的是
  `parsePayload(uiState.pathOrUrl)`（始终是初始地址）。
- **修复**：`submitUrl()` 先把输入框内容同步进 `pathOrUrl` 并清空上一轮结果，
  再递增 `parseRequestId`：
  ```kotlin
  fun submitUrl() {
      val target = _uiState.value.inputUrl.trim()
      if (target.isEmpty()) return
      _uiState.value = _uiState.value.copy(
          pathOrUrl = target,
          parseRequestId = _uiState.value.parseRequestId + 1,
          isParsing = false,
          error = null,
          archiveInfo = null,
          partitions = emptyList(),
          filteredPartitions = emptyList(),
          selectedPartitions = emptySet(),
          selectedPartition = null
      )
  }
  ```

### Bug 4：`LaunchedEffect` 无限重解析循环
- **现象**：解析出错 → 分区列表为空 → 触发再次解析 → 死循环。
- **修复**：用 `parseRequestId` 计数器驱动解析（仅在 `parseRequestId > 0` 时触发），
  错误分支只更新 `error`/`isParsing`，不再间接触发新的解析请求。

## 4. UI 交互要点

- 顶部：`TextField`（miuix 用 `label` 而非 `placeholder`）+ "解析" 按钮。
- 解析中：`CircularProgressIndicator` + 文案。
- 错误态：红色（`Color(0xFFE53935)`）`Card` 显示 ⚠ + `uiState.error`，同时弹
  `Toast.LENGTH_LONG`。
- 点击分区项 → `selectPartition(name)` 弹出 `PartitionDetailDialog`（底部弹层）：
  显示 size / rawSize / ops 数 / 完整 SHA256 / 各 operation type 统计。
- 多选 → 底部 `Card` 显示"已选 N 项" + "提取选中"按钮。

## 5. 离线分析脚本（根目录）

- `probe_zip.py`：确认 A/B 包 payload 真实偏移（=61，stored）。
- `probe_zip2.py` / `probe_zip2_zip64.py`：确认 2024 recovery 包是 ZIP64 + DEFLATE、
  无 payload.bin。
  用 Python 的 `zipfile` 离线读 central directory，比在设备上反复构建高效。

## 6. 调试日志保留策略

按用户要求保留解析关键节点的 `Log.i("VivoPayload", ...)`：
`parseFromUrl: start / http init done / payloadOffset / initPayload done /
getPartitionInfoList done`，以及 `getPayloadOffset` 内对探测偏移处 32 字节、
前 64 字节的 hex dump（`Log.d` 级别，避免日志过噪）。
真机定位时：`adb logcat | grep VivoPayload`。
