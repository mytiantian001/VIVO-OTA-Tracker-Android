# ADR-001：在线 OTA zip 包内文件浏览与文本预览

- **状态**：已接受
- **日期**：2026-08-29
- **相关**：`docs/PAYLOAD_PARSING.md`、`docs/GLOSSARY.md`

## 背景

项目已支持「查看 OTA 包分区列表」（`PayloadDumperScreen`），但存在三个缺口：

1. 只能看到 payload.bin 解析出的**分区**，看不到 **zip 容器本身**有哪些条目；
2. recovery 全量包（无 payload.bin）只能给出「无法解析」的错误提示；
3. 分区提取功能依赖 `Environment.getExternalStoragePublicDirectory`，在 `targetSdk=37`
   的分区存储约束下必然 Permission denied，**实际从未成功过**。

同时实测数据（`vivo_payload_log.txt`）显示 vivo OTA 包体积为
**4.31 / 6.32 / 6.50 / 7.22 / 11.62 GB**，整包下载解压在手机上不可行。

## 约束（决定性的硬事实）

| 事实 | 影响 |
|------|------|
| 包体 4.3 ~ 11.6 GB | 「下载整包 → 解压」路径否决 |
| `AndroidManifest.xml` 仅声明 `INTERNET`，`targetSdk=37` | 现有提取落盘路径失效，必须改用 MediaStore |
| zip 每个条目**独立压缩** | DEFLATE 条目可单独流式 inflate，无需下载整个包 |
| 无成熟 Android 端 ext4/erofs 只读库 | 「浏览分区镜像内文件树」需自研，判定为周级工作量，本 ADR 不涉及 |

## 决策

### D1：目标层级定为 L0（容器条目列表），不做 L2（分区内文件树）

只读取并解析 zip **central directory**，列出全部条目，不下载任何数据（秒出）。

- **不做** L2（分区镜像内文件树浏览）：需自研 ext4 解析器，且 Android 12+ 的
  `system`/`vendor` 可能是 erofs，无公开 Kotlin 实现，工作量周级。
- **不做** L3（整包下载解压）：11.6 GB 物理不可行。
- **后果**：A/B 包下用户看不到 payload.bin 内部内容，只能看到包结构与元数据。已与用户确认接受。

### D2：两类包（A/B 与 recovery 全量）都支持，不做区分

L0 只依赖 central directory，与包内是否有 payload.bin 无关。
recovery 包至少能展示「221 个 `.img`，是 recovery 全量包」，优于当前的纯错误提示。

> 本条**修正**了 `PAYLOAD_PARSING.md:39` 中记录的旧决策（「recovery 包只报错不解析」）。

### D3：下载红线 10 MB，压缩后与解压后尺寸「任一超标即禁」

- 同时比对 `compressedSize`（网络流量）与 `uncompressedSize`（落盘占用）。
- UI 展示两个尺寸（如 `2.1 MB → 8.7 MB`）。
- **额外防线**：解压时实时统计产出字节数，超过 10 MB 立即中止并删除产物。
  仅依赖 header 中的 `uncompressedSize` 不足以防 zip bomb（该字段可伪造）。

### D4：下载落点用 MediaStore.Downloads（Android 10+），低版本回退私有目录

文件均 ≤10 MB，可整块读入内存后一次性写出，**无需随机写**，
因此不触发 SAF 方案下重写 `extractPartition` 写入层的成本。

### D5：文本预览，且仅预取前 8 KB

- STORED 条目：`Range` 直读前 8 KB。
- DEFLATE 条目：`Range` 读取条目起点起约 32 KB 压缩数据 → 流式 inflate
  → **读满 8 KB 明文立即中止并关闭连接**（实际流量约 2~4 KB）。
- 提供「加载完整内容」按钮按需升级为全量（仍受 D3 红线约束）。
- **不做**分块追加（OTA 元数据文件普遍很小，收益不抵复杂度）。

### D6：可预览判定 = 扩展名白名单优先 + 内容嗅探兜底 + NUL 字节硬拦截

- 白名单：`.txt .prop .sh .rc .xml .json .cfg .conf .csv .log .mf .sf`
- 非白名单走嗅探（可打印字符比例），使 `updater-script`、
  `META-INF/com/android/metadata` 等**无扩展名**的关键文件可被预览。
- **任何情况下检出 NUL 字节一律拒绝预览**——防止 `apex_info.pb`、`care_map.pb`
  等二进制 protobuf 因可打印字节占比高而被误判为文本。

### D7：文本编码自动检测（BOM → 严格 UTF-8 校验 → GBK 回退）

OTA 包内 txt 几乎全为 ASCII（UTF-8 子集），自动检测覆盖 99% 场景；
GBK 回退用于兜底 vivo 元数据中的中文注释。

### D8：预览可复制 = 长按拖选 + 一键复制全部

- 预览区必须包 `SelectionContainer`：**Compose 的 `Text` 默认不可选中**，
  项目此前从未使用过该组件，现有界面所有文字均无法拖选。
- 复用 `VivoApp.kt:550` 的 `copyToClipboard()`（含触感反馈）与
  `R.string.copied`（已 11 语言翻译）。
- **Binder 事务上限约 1 MB**：>128 KB 的文本不显示「复制全部」按钮，
  并以 `try-catch` 兜住 `TransactionTooLargeException`，避免 9 MB 文本触发崩溃。

### D9：预览被截断时，「复制」复制的是已加载部分

按钮文案随状态变化（「复制已加载的 8 KB」/「复制全部 (48 KB)」）。
不选「自动先加载完整再复制」——那会让用户以为是复制、实际触发完整下载，
与 D3 红线的谨慎意图相悖。

### D10：UI 落位 = Dumper 页可折叠区块，默认折叠

位于 ROM 信息卡下方、分区列表上方，标题「包内文件 (N)」，
带搜索框（221 个条目无搜索等于不可用）。
同时接上分区列表中长期空挂的 `searchQuery` 字段。

### D11：只做单选下载，不做多选批量

A/B 包中 ≤10 MB 的条目通常仅 5~8 个，recovery 包的 `META-INF/**` 仅三四个，
批量下载收益近零却需重复一套全选/批量进度 UI。

### D12：下载完成后展示 CRC32 校验结果

成本近零，且可佐证流式 `Range` 拼接未错位——这是当前架构最易出错处。

## 技术债清单（本 ADR 范围内一并修复）

| # | 位置 | 问题 |
|---|------|------|
| 1 | `VivoPayloadUtil.locateLocalFileHeader` | 无 `remaining()` 边界检查，条目多时 `BufferUnderflowException` |
| 2 | 同上 | 遇到非 `CENSIG` 立即 `break` 而非跳过，单字节错位即整段遍历失效 |
| 3 | `VivoPayloadUtil.locateCentralDirectory` | ZIP64 分支硬编码 `4096 - (fileLength - zip64EndSigOffset)` |
| 4 | `VivoPayloadHttpUtil` | 单例 + 单 `position` 游标，列表解析与下载无法并发，需外层互斥 |
| 5 | ZIP64 extra field (0x0001) 未解析 | 条目 ≥4 GB 时读到 `0xFFFFFFFF`；recovery 包 `super.img` 常 5~6 GB，必现 |
| 6 | raw deflate | zip 条目 DEFLATE 无 zlib 头，必须 `Inflater(true)`，否则报 unknown compression method |

## 后果

**正面**
- 零新增权限、零整包下载，核心浏览流量在 KB 级。
- recovery 全量包从「完全不可用」变为「结构可见 + 元数据可下载」。
- 下载走 MediaStore，产物用户可见，且不依赖任何存储权限。

**负面 / 代价**
- A/B 包内 payload.bin 的内容仍不可见（需 D1 中排除的 L2 能力）。
- 现有「分区提取」落盘路径（技术债 4 相关）仍不可用，本 ADR **不修复**它；
  该功能需另立 ADR 迁移到 MediaStore/SAF。
- 预览与下载共用单例 HTTP 游标，需串行化，不支持并发。
