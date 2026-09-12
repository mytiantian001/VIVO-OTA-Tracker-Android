# ADR-004：分区列表解析统一到 ZipByteSource（远程 OTA 解析架构收敛）

- **状态**：提议
- **日期**：2026-09-12
- **相关**：`docs/ADR-001-ota-zip-browser.md`、`docs/ADR-002-ota-zip-file-manager-ui.md`、
  `docs/PAYLOAD_PARSING.md`、`docs/REMOTE_ZIP_STREAM_PARSING.md`、`docs/GLOSSARY.md`

## 背景

ADR-001 交付的「包内文件浏览」引入了 `VivoZipBrowser` 与 `ZipByteSource` 抽象，
并修掉了 `ADR-001` 技术债清单里的 1、2、5、6。但**分区列表（payload 解析）这条更老的路径
完全没有吃到这些改进**，仓库里因此并存两套 zip 解析实现：

| | `PayloadUtil`（分区列表在用） | `VivoZipBrowser`（包内文件浏览在用） |
|---|---|---|
| 随机读 | 吃 `VivoPayloadHttpUtil` 单例 + 单 `position` 游标 | `ZipByteSource.readAt(offset, out)`，无游标 |
| CENSIG 失配 | `break`，整段遍历失效（技术债 2） | 扫描前进 1 字节继续（已修） |
| ZIP64 extra `0x0001` | **不解析**（技术债 5） | 已解析（已修） |
| 中央目录大小上限 | 无 | 8 MB（已修） |
| local header 读取 | 固定 256 KB | 按 nameLen/extraLen 精确算，上限 64 KB |

结果：同一个 bug 要修两遍，且**修了新路径、漏了老路径**（技术债 5 即为此）。

同时，外部参考 `VioletToolBox`（C#，见 `docs/REMOTE_ZIP_STREAM_PARSING.md`）用
`IRandomAccessReader` 抽象把同一条路走通了，其健壮性细节（强制 206、块缓存、重试）
是本仓库缺失的。本 ADR 收敛到一套实现，并吸收其防护。

## 关键事实（决定方案边界）

| 事实 | 影响 |
|------|------|
| 远程 OTA 包 4.3 ~ 11.6 GB | 「下载整包 → 解压」路径否决，必须全程 Range 随机读 |
| `payload.bin` 在 OTA zip 中普遍为 **STORED** 存放 | 可零拷贝切片；但必须显式校验，不能靠运气 |
| 单次分区列表解析实际只需「尾部 EOCD + 中央目录 + 数十字节头 + manifest」 | 当前多发的 256 KB 属纯浪费，应消除 |
| 手机端网络为移动网络，RTT 与限流远不如桌面稳定 | 请求数与重试策略是一等公民，不是优化项 |
| `VivoPayloadHttpUtil` 是单例 + 单游标 | 任何未持锁的调用路径都会串位，靠 `zipMutex` 外部约定不可靠 |

## 现状：一次「查看分区列表」到底发了什么

`VivoPayloadViewModel.parsePayload` →

| # | 动作 | 请求数 | 流量 |
|---|------|--------|------|
| 1 | `VivoPayloadHttpUtil.init`（Range 0-0） | 1 | 1 B |
| 2 | 读尾部 4096 B 找 EOCD | 1 | 4 KB |
| 3 | 读整个 central directory | 1 | 数十 KB ~ 数 MB |
| 4 | 读 local header（**固定 256 KB**） | 1+ | **256 KB** |
| 5 | 调试探针 32 B | 1 | 32 B |
| 6 | 调试探针前溯 64 B | 1 | 64 B |
| 7~12 | CrAU 头 4+8+8+4 B、manifest、signature | 6 | manifest 大小 |
| | **合计** | **≈11** | **CD + manifest + 256 KB** |

问题不在总量（manifest 本就必要），而在**可消除的浪费与可避免的失败**：

- 第 4 步 256 KB 是历史包袱（`PAYLOAD_PARSING.md:233` 记录的"vivo extra field 可达数万字节"），
  正确解法是「先读 1 KB，不够再按 nameLen+extraLen 精确补读」，不是无脑 256 KB；
- 第 5、6 步是调试残留，常驻生产路径；
- 每次请求都是**独立 HTTP 往返**，无缓存，`parsePayload` 与 `loadZipEntries` 会把
  尾部 EOCD + 中央目录各读一遍。

## 决策

### D1：`ZipByteSource` 是唯一随机读抽象，删除 `PayloadUtil` 中的 zip 解析部分

`PayloadUtil` 保留 CrAU 头解析、protobuf 解析、分区提取（`extractPartition`），
**删除** `locateCentralDirectory` / `locateLocalFileHeader` / `locateLocalFileOffset`，
zip 部分统一由 `VivoZipBrowser` 提供。

> 不重写解析算法——两套算法的顶层流程本来就一致（尾部 EOCD → CD → 找 payload.bin →
> LFH 算 dataOffset → CrAU → manifest），差异全在工程细节，合并即可。

### D2：分区定位复用 `listZipEntries` 的结果，一次 CD 解析两处共享

在条目里找 `name.endsWith("payload.bin")`，直接复用已解析好的
`localHeaderOffset`（含 ZIP64 修正）、`uncompressedSize`、`method`。
`readEntryDataOffset()` 由 `private` 放开为 `internal` 复用。

收益：ZIP64 隐患自动消失；`parsePayload` 与 `loadZipEntries` 不再各读一遍 CD。

### D3：新增 `SubrangeByteSource`，把 payload.bin 区间表达成独立 source

```kotlin
class SubrangeByteSource(
    private val base: ZipByteSource,
    private val baseOffset: Long,
    override val size: Long
) : ZipByteSource {
    override suspend fun readAt(offset: Long, out: ByteArray): Int =
        base.readAt(baseOffset + offset, out)
}
```

payload 头 / manifest 的读取全部在它之上进行，语义等价于 VioletToolBox 的 `SubrangeReader`
（其"零拷贝切片"正是远程可行性的关键）。

### D4：HTTP 层强制 206，新增 `RANGE_NOT_SUPPORTED` 错误标记

`VivoPayloadHttpUtil.init` 当前只判 `response.isSuccessful`——**200 与 206 都放行**。
服务器忽略 Range 返回 200 时，`fileLength` 回退到 `Content-Length`（数值恰好正确，故静默），
但后续所有 `readSync` 的 Range 头同样被忽略 → 每次都从文件起始读 → 偏移全部错位 →
用户看到的是 `NOT_A_VALID_ZIP` 这类**完全误导**的提示。

改为：`code != 206` 即抛 `IOException("RANGE_NOT_SUPPORTED")`，
在 `VivoPayloadViewModel.mapErrorMessage` 增加：「该下载服务器不支持分段读取，请更换直链或下载到本地后再解析。」
`readSync` 同样校验 206。

### D5：local header 先读 1 KB，不够再精确补读；删除两处调试探针

`LOCAL_HEADER_READ_BYTES` 从 256 KB 降为 1 KB；若 `30 + nameLen + extraLen > 读到的长度`，
按实际值补读一次。删除 `getPayloadOffset` 中的 32 B / 64 B 探针及其 hex dump。

### D6：在 `HttpByteSource` 外套块缓存（1 MB × 4 块 LRU）

手机内存紧张，缓存容量取 VioletToolBox（8 块）的一半。
大块顺序读（>4 MB，如 manifest 或提取）绕过缓存直接请求。
收益：CrAU 头、manifest、signature 的多次小读合并；`parsePayload` 与 `loadZipEntries`
共享尾部 EOCD / 中央目录。

### D7：重试与退避

3 次重试；优先用响应头 `Retry-After`，否则指数退避 450ms 起、封顶 4s；
覆盖 429、超时、`IOException`。移动端比桌面更需要——VioletToolBox 在桌面上都做了。

### D8：显式校验 `payload.bin` 为 STORED

`method != 0` 时直接抛 `PAYLOAD_NOT_STORED`，文案：「该包的 payload.bin 是压缩存放，
无法在线解析，请下载到本地后使用。」当前未校验，只会在后面撞上 `Invalid magic value`。

### D9：统一流量统计（可选但廉价）

在 `ZipByteSource` 实现层累加 `bytesRead`，UI 显示"实际下载 X KB / 包体 Y GB"。
这是让用户确信"没在下整包"的最直接证据。

### D10：不做的事

- **不做**本地文件支持（`FileByteSource`）：本项目定位是在线查询工具，无本地导入入口；
- **不做**分区提取并发化：当前 `extractFromHttp` 串行 + 全局 mutex，提速需重写为多 worker + 随机写，
  与 VioletToolBox 的 `ProcessorCount` 并发对齐，收益/成本比不足，另立议题；
- **不替换** `PayloadUtil` 的 CrAU / protobuf / 提取实现，只剥离其 zip 部分。

## 与 VioletToolBox 方案的对比结论

| 维度 | 本仓库现状 | VioletToolBox | 收敛后 |
|------|-----------|---------------|--------|
| 随机读抽象 | 单例游标（部分路径已有 `ZipByteSource`） | `IRandomAccessReader` 全链路 | `ZipByteSource` 全链路（D1） |
| Range 校验 | 无（200 放行） | 强制 206 | 强制 206（D4） |
| ZIP64 extra | 仅新路径有 | 有 | 全路径有（D2） |
| local header | 256 KB | 30 B | 1 KB + 精确补读（D5） |
| 块缓存 | 无 | 1 MB × 8 | 1 MB × 4（D6） |
| 重试 / 429 | 无 | 3 次 + 退避 | 同（D7） |
| STORED 校验 | 无 | 有 | 有（D8） |
| 本地文件 | 不支持 | 支持 | 不做（D10） |
| zip bomb 产出上限 | **有**（ADR-001 D3） | 无 | 保留（本仓库更严） |
| 中央目录上限 | 8 MB | `int.MaxValue` | 保留 8 MB |

**不是二选一，而是合并后两边都更强**：`VivoZipBrowser` 已具备 VioletToolBox 没有的
zip bomb 防护与更严的目录上限，VioletToolBox 提供了本仓库缺的 206 校验、缓存、重试。

## 实施步骤

| 阶段 | 内容 | 涉及文件 | 预估 |
|------|------|----------|------|
| P0 | `SubrangeByteSource`（D3）；分区定位改走 `listZipEntries` + `readEntryDataOffset`（D2/D1）；删 256 KB 与探针（D5）；STORED 校验（D8） | `VivoZipBrowser.kt`、`VivoPayloadUtil.kt`、`VivoPayloadViewModel.kt` | 半天 |
| P1 | 206 强校验 + 错误文案（D4） | `VivoPayloadHttpUtil.kt`、`VivoPayloadViewModel.kt` | 1~2 小时 |
| P2 | 块缓存（D6）+ 重试退避（D7）+ 流量统计（D9） | 新增 `CachingByteSource.kt`、`VivoPayloadHttpUtil.kt` | 半天 |

**验收标准**（以 `vivo_payload_log.txt` 同口径核对）：

1. 一次「查看分区列表」的 HTTP 请求数 **≤ 6**（当前 ≈11），且不再出现 256 KB 量级的单次读取；
2. 同一 URL 先「查看分区列表」再展开「包内文件」，中央目录**只读取一次**（缓存命中或结果复用）；
3. 对返回 200 的模拟端点，报错为「不支持分段读取」而非 `NOT_A_VALID_ZIP`；
4. 现有 A/B 包链接解析结果与改造前**逐分区一致**（分区名、size、rawSize、sha256 全等）；
5. `Log.i("VivoPayload", ...)` 关键节点保留，便于 `adb logcat | grep VivoPayload` 回归。

## 实施进度

| 阶段 | 状态 | 说明 |
|------|------|------|
| P0 | ✅ 已完成（2026-09-12） | `SubrangeByteSource`；分区定位改走 `listZipEntries` + `readEntryDataOffset`；删 256 KB 固定读与两处调试探针；STORED 校验；`locateCentralDirectory` 由 `PayloadUtil` 迁入 `VivoZipBrowser` |
| P1 | ✅ 已完成 | `VivoPayloadHttpUtil.init` 与 `readSync` 均强制 206；总长只取 `Content-Range`（不再回退 `Content-Length`）；新增 `RANGE_NOT_SUPPORTED` / `RANGE_INVALID_OFFSET`(416) / `RANGE_MISMATCH`(区间起点不符) / `SOURCE_CHANGED`(会话号) 标记，双通道文案（`mapErrorMessage` 与 `mapZipErrorRes`）齐备 |
| P2 | ✅ 已完成（2026-09-12） | `CachingByteSource`（1 MB × 4 LRU，>4 MB 直通）；`VivoPayloadHttpUtil` 失败分类 + 重试退避（3 次、`Retry-After` 优先、封顶 4 s）；`bytesRead()` 会话流量并在 ROM 信息卡展示（10 语言） |

> P1 实际实现范围**超出**本 ADR 的要求：除 206 强校验外，还增补了
> `Content-Range` 起点校验（防 Range 拼接错位）、416 区分、以及 `sessionId()` 会话号
> （防止 `ZipByteSource` 持有上一次的条目数据把旧偏移读成新包内容）。

**离线验证**：`verify_adr004_payload_locate.py`（8/8 通过），对照 OLD/NEW 两组同构实现：

| 用例 | 结果 |
|------|------|
| 常规包（STORED，extra 20B） | OLD == NEW == **61**，与 `vivo_payload_log.txt` 历史基线一致 |
| 超长 extra（50000B，包体 >256KB） | 结果一致；流量 **266 KB → 55 KB** |
| ZIP64（lho = 0xFFFFFFFF） | NEW 正确取到 61；OLD 越界崩溃（已知缺陷，现已消除） |
| payload.bin 为 DEFLATE | NEW 明确抛 `PAYLOAD_NOT_STORED` |
| 无 payload.bin（recovery 包） | 两组均 `NOT_A_PAYLOAD_ZIP` |

**离线验证**：`verify_adr004_p2.py`（17/17 通过），覆盖重试分类与块缓存：

| 用例 | 结果 |
|------|------|
| 429 / 503 可重试 | ✅ 不再误报成「服务器不支持分段读取」 |
| `Retry-After: 2` | 采纳为 2000 ms 等待 |
| 连续 5xx 重试耗尽 | 收敛为 `NETWORK_ERROR`（并独立文案） |
| 200（Range 被忽略） | **只请求一次**，直接抛 `RANGE_NOT_SUPPORTED` |
| 416 | `RANGE_INVALID_OFFSET` |
| 退避序列 | 450 / 900 / 1800 ms，封顶 4000 ms |
| 同块重复读 | 底层只请求一次 |
| 跨块读 / >4 MB 直通 / LRU 上限 4 块 / 命中续期 | 均符合预期 |

**待真机验证**：验收标准 1、2、4——需用同一 vivo 直链对比
分区名 / size / rawSize / sha256 是否逐项一致；`adb logcat | grep VivoPayload` 核对
请求次数（应 ≤6）；先解析再展开「包内文件」时 central directory 应命中缓存不再重复请求。

## 风险与回滚

| 风险 | 应对 |
|------|------|
| 合并过程引入偏移回归 | 先加验收标准 4 的对照测试（改造前后打印分区指纹），再动代码 |
| `readEntryDataOffset` 放开后被调用方误用 | 保持入参为 `ZipEntryInfo`，偏移计算仍收敛在 `VivoZipBrowser` 内部 |
| 块缓存掩盖"读越界"类 bug | 缓存只在 `HttpByteSource` 外层，且 key 含绝对块号；先做 P0/P1 再上 P2 |
| 1 KB local header 对超长 extra field 不够 | 已有精确补读兜底；日志记录实际 nameLen/extraLen |

回滚：P0/P1/P2 各自独立可关；P0 风险最高，保留 `PayloadUtil` 旧路径一个版本周期，
通过开关切换，验证无误后删除。

## 后果

**正面**
- zip 解析从两套收敛为一套，技术债 1/2/5/6 的修复真正覆盖全路径；
- 单次分区列表解析流量与请求数显著下降（去掉 256 KB 固定读 + 2 次探针 + 重复的 CD 读取）；
- 服务器不支持 Range 时给出可操作提示，不再出现误导性的 `NOT_A_VALID_ZIP`。

**负面 / 代价**
- `PayloadUtil` 与 `VivoZipBrowser` 的职责边界需重新划分，短期内存在过渡期双路径；
- 块缓存引入新的状态，需与 `zipMutex` 的串行约定一并梳理（长期应随无游标模型废弃该锁）。
