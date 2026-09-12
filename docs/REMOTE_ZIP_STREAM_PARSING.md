# 远程 zip 刷机包的流式解析思路（以 VioletToolBox 实现为参考）

- **状态**：参考资料
- **日期**：2026-09-12
- **来源**：`F:\learn-front\learn-hook\VioletToolBox\VioletToolBox`（C# / WPF 刷机工具箱，Payload Dumper 模块）
- **相关**：`docs/PAYLOAD_PARSING.md`、`docs/ADR-001-ota-zip-browser.md`、
  `docs/ADR-004-unify-remote-zip-parsing.md`（本方案在本仓库的落地决策）、`docs/GLOSSARY.md`

## 0. 为什么写这份文档

本项目（VIVO-OTA-Tracker-Android）同样要在**手机端**解析 GB 级远程 OTA zip（实测 4.31 ~ 11.62 GB，
见 `ADR-001:16`），已确定「不下载整包」。VioletToolBox 在桌面端把这条路径完整走通了，
其分层方式与几个工程细节（Range 能力探测、块缓存、ZIP64、Stored 判定、懒加载落盘）
对本项目有直接借鉴价值。本文按「它怎么做的 + 我们能抄什么」两条线整理。

> 注：本文描述的是 **VioletToolBox 的实现**，不是本项目现状；项目内现状以
> `PAYLOAD_PARSING.md` 与 `ADR-001` 为准。

## 1. 核心思想：把远程文件伪装成随机读接口

整个设计的地基是一个抽象：

```csharp
public interface IRandomAccessReader : IAsyncDisposable
{
    ValueTask<long> GetSizeAsync(CancellationToken ct);
    ValueTask<int>  ReadAsync(long offset, Memory<byte> buffer, CancellationToken ct);
    long BytesRead { get; }     // 便于统计真实流量
}
```

四种实现，上层**完全不关心数据来自本地还是 HTTP**：

| 实现 | 用途 |
|------|------|
| `LocalFileReader` | 本地文件（`RandomAccess.ReadAsync`，异步 + 随机访问） |
| `TempFileReader` | 解压出的临时文件，Dispose 时自动删除 |
| `HttpRangeReader` | 远程 URL，靠 HTTP Range 拼出随机读 |
| `SubrangeReader` | 在任意 reader 上切出 `[baseOffset, baseOffset+length)` 子区间 |

收益：ZIP 解析、payload 解析、条目导出三套逻辑**只写一遍**，本地/远程同码同测。

**对本项目**：对应 `VivoPayloadHttpUtil`，但目前是「单例 + 单 position 游标」（`ADR-001` 技术债 4），
列表解析与下载无法并发。VioletToolBox 的做法是直接把 `position` 游标概念消灭掉——
所有读都是 `(offset, buffer)` 显式寻址，值得在重构时照抄。

## 2. 完整流程

```
URL 输入
 └─1  PreparePayloadRemoteSourceAsync     清洗链接 / 解析跳转直链 / 组装 headers
 └─2  OpenSourceAsync                     本地 → LocalFileReader；http(s) → HttpRangeReader
 └─3  HttpRangeReader.CreateAsync         Range 0-0 探测：要求 206，从 Content-Range 取总长
 └─4  ZipStoredEntryLocator               尾部 EOCD → [ZIP64] → 中央目录 → 定位 payload.bin
                                          ↓(Stored)                ↓(DEFLATE)
 └─5  SubrangeReader(source, off, size)   零拷贝切片               远程：报错提示下载本地
 └─6  ReadManifestAsync                   读 24B CrAU 头 → manifest(protobuf) → dataOffset
 └─7  GetPartitions                       列分区 → 用户勾选 → 按 Range 拉取分区数据
 └─(旁路) META-INF/com/android/metadata   机型/代号/安卓版本/安全补丁
 └─(旁路) 通用 ZIP 模式                   无 payload.bin 时列条目、按需提取
```

### 2.1 URL 预处理

`PreparePayloadRemoteSourceAsync`（`MainWindow.Payload.cs:115`）：

1. `CleanLink` 清洗粘贴进来的脏链接；
2. 识别魅族下载入口页：用 `AllowAutoRedirect = false` 手工发一次 GET，
   取 301/302/303/307/308 的 `Location` 换成真实 CDN 直链，并带上站点要求的 Referer；
3. `NormalizeUrlForRequest` 归一化。

**要点**：跳转解析与真正的数据读取**分离**，避免 Range 请求打到会 302 的入口页上
（很多 Range 实现遇到重定向会退化成 200 全量）。

### 2.2 打开数据源

```csharp
if (File.Exists(pathOrUrl)) return new LocalFileReader(pathOrUrl);
if (url.StartsWith("http://") || url.StartsWith("https://"))
    return await HttpRangeReader.CreateAsync(url, headers, ct);
```

### 2.3 远端能力探测（一次请求定生死）

```csharp
probe.Headers.Range = new RangeHeaderValue(0, 0);     // 只求 1 字节
if ((int)resp.StatusCode != 206) throw ...("远程不支持 Range 请求");
long? totalLength = resp.Content.Headers.ContentRange?.Length;   // 取总长
if (!totalLength.HasValue || totalLength <= 0) throw ...("远程没有 Content-Length");
```

失败直接给出可操作提示（「服务器不支持分段读取，请先下载到本地」），
而不是让用户等几分钟后才发现失败。**总长度来自 `Content-Range`，不依赖 `Content-Length`**——
因为 206 响应里 `Content-Length` 是本次片段长度。

### 2.4 HttpRangeReader 的读取策略

- **块缓存**：1 MB 一个块，LRU 最多 8 块；≤4 MB 的读走缓存，>4 MB 直接发 Range
  （大块顺序读缓存命中率低，缓存反而浪费内存）。
- **串行化**：所有请求过一把 `SemaphoreSlim`，避免并发打爆连接 / 触发限流。
- **重试**：429、超时（`TaskCanceledException`）、`HttpRequestException` 各最多重试 3 次；
  优先用响应头 `RetryAfter.Delta`，否则指数退避 450ms → 上限 4s。
- **流量可观测**：`BytesRead` 用 `Interlocked` 累加，UI 直接显示实际下载量与速度。

### 2.5 手写 ZIP 结构解析（不依赖 ZipArchive）

`ZipStoredEntryLocator` 直接对 `IRandomAccessReader` 按偏移读，所以本地/远程同一套代码：

1. **找 EOCD（22 字节）**：先试文件尾部；不是则回扫尾部 `64KB + 22`，
   用「注释长度字段 == 实际剩余长度」反推位置（正确处理带注释的 zip）。
2. **ZIP64 判定**：`totalEntries == 0xFFFF || cdSize == 0xFFFFFFFF || cdOffset == 0xFFFFFFFF`
   → 往前 20 字节读 ZIP64 Locator → 读 56 字节 ZIP64 EOCD，取 64 位条目数/目录大小/目录偏移；
   中央目录项再解析 extra field `0x0001`，按 `0xFFFFFFFF` 占位出现的顺序依次补齐
   uncompressedSize / compressedSize / localHeaderOffset。
3. **遍历中央目录**（`0x02014b50`），读压缩方法、压缩/原始大小、文件名、extra、LFH 偏移。
4. **匹配 `payload.bin`**：先精确名匹配，失败回退后缀匹配（`EndsWith`）。
5. **硬性校验 `compression == 0`（Stored）**，非 Stored 直接抛错。
6. **算数据偏移**：读该条目的 LocalFileHeader（30 字节）取 `nameLen` / `extraLen`：

   ```
   dataOffset = lfhOffset + 30 + fileNameLen + extraLen
   ```

   返回 `(dataOffset, uncompressedSize)`。

> 本项目 `PAYLOAD_PARSING.md:43` 记过同类坑：local file header 字段偏移算错
> （`+26` 应为 `+22`），实测真实 `dataOffset = 61`。跨语言复现时的第一嫌疑点。

### 2.6 从 ZIP 到 payload reader

```csharp
bool isZip = await IsZipAsync(source);          // 读头 4 字节判 PK\x03/05/07
try  { var (off,size) = 定位 payload.bin; return (new SubrangeReader(source, off, size), size); }
catch {
  if (isZip) {
     try 后缀匹配...
     if (source is LocalFileReader lf) {
        if (!eagerExtract) return (new ZipPayloadLazyReader(lf.Path, log), size);   // 惰性
        return (new TempFileReader(解压到临时文件), size);                            // 急切
     }
     throw new InvalidDataException("检测到输入是 ZIP，但无法定位 payload.bin。请先下载并解压...");
  }
  return (source, await source.GetSizeAsync());   // 输入本来就是裸 payload.bin
}
```

三条路径的分工：

| 场景 | 处理 | 说明 |
|------|------|------|
| 远程 ZIP + `payload.bin` Stored | `SubrangeReader` | **零拷贝**，全程不下整包 |
| 本地 ZIP + `payload.bin` Stored | 同上 | 同上 |
| 本地 ZIP + `payload.bin` DEFLATE | `ZipPayloadLazyReader` 或解压到临时文件 | 有本地文件才可能解压 |
| 远程 ZIP + `payload.bin` DEFLATE | **报错，提示下载到本地** | 远端无法随机解压 |

`ZipPayloadLazyReader` 的惰性策略很值得抄：

- 先用 `ZipArchive` **只读条目长度**当 `size`（不读数据）；
- 访问落在前 **32 MB** 内 → 边 inflate 边填 `MemoryStream` 缓存（解析 manifest 通常只碰前几 MB）；
- 一旦访问越过 32 MB（或超 int 范围）→ `EnsureExtractedAsync` 才真正解压到临时文件，
  用 `TempFileReader` 接管，Dispose 时自动删除。

即「能不落盘就不落盘，真需要时再一次性落盘」。

### 2.7 解析 payload（CrAU + protobuf）

只发一次 24 字节的 Range 请求：

```
[0..4)   magic "CrAU"                  否则报错（若读到 PK 头，提示"请确认选的是 payload.bin"）
[4..12)  file_format_version (BE u64)  必须 == 2
[12..20) manifest_size      (BE u64)
[20..24) metadata_signature_size (BE u32)
dataOffset = 24 + manifest_size + metadata_signature_size
```

随后按 `manifest_size` 再拉一次字节，`DeltaArchiveManifest.MergeFrom(bytes)` 解析 protobuf。
`GetPartitions` 遍历 `manifest.Partitions`，用每个 operation 的 `DstExtents`
求 `max(startBlock + numBlocks) * blockSize` 得到分区字节大小。

**错误提示的差异化**（值得借鉴）：magic 是 `PK` 时给出「你是不是选错了文件」，
比笼统的「解析失败」可操作得多；本项目 `PAYLOAD_PARSING.md:35` 的
`NOT_A_PAYLOAD_ZIP` / `NOT_A_VALID_ZIP` 标记是同一思路的 Kotlin 版。

### 2.8 旁路一：metadata（机型/版本/补丁）

`META-INF/com/android/metadata` 同样走 `ZipStoredEntryLocator` 定位 + 直读。
远程 ZIP 中该条目若被压缩，明确提示「远程 ZIP 的 metadata 可能被压缩，暂不支持直接提取」，
而不是静默失败。

### 2.9 旁路二：通用 ZIP 模式（无 payload.bin）

对「远程 URL 以 `.zip` 结尾 + 条目里没有 `payload.bin`」（典型 recovery 全量包）：

1. `ListEntriesAsync` 拉中央目录，条目直接当「可勾选列表」展示；
2. 导出用 `ExtractEntryAsync`：
   - Stored → `CopyRangeAsync` 按 1 MB 分块 Range 拉取，直写输出流；
   - DEFLATE(8) → 用 `RandomAccessSliceStream` 把「远端某区间」包装成只读 Stream，
     再接 `DeflateStream` 边下边解压（**非压缩条目可真正随机访问，压缩条目只能顺序流式**）。

这与本项目 `ADR-001` 的 D1/D2（L0 条目浏览、两类包都支持）是同一决策，
只是 VioletToolBox 把它做成 payload 解析失败后的**兜底分支**。

## 3. 关键设计约束（踩坑红线）

| 约束 | 原因 | 违反后果 |
|------|------|----------|
| `payload.bin` 必须 **Stored** 存放 | 远程无法随机解压 DEFLATE | 只能报错让用户下载本地（主流 A/B OTA 包均满足） |
| 服务器必须支持 **Range** 且返回 206 | 否则退化为整包下载 | 探测阶段直接失败，提示本地导入 |
| 总长取 `Content-Range` 而非 `Content-Length` | 206 下后者是片段长度 | 长度算错，后续所有偏移越界 |
| 中央目录大小需做 `int.MaxValue` 上限校验 | 防止异常值导致巨额分配 | 内存爆炸 / 长时间无响应 |
| 缓存/解压要有软上限（32 MB） | 控制内存峰值 | 大包解析 OOM |
| 解压产出要**实时计数**防 zip bomb | header 里的 `uncompressedSize` 可伪造 | 磁盘被打满（本项目 ADR-001 D3 同款防线） |

## 4. 对本项目（Kotlin / Android）的可借鉴点

按优先级：

1. **消灭 position 游标**：把 `VivoPayloadHttpUtil` 的单例游标模型换成
   `(offset, length)` 显式寻址的 `IRandomAccessReader` 等价物（`OkHttp` + `Range` 头即可），
   顺带解决 `ADR-001` 技术债 4 的并发互斥问题。
2. **Range 能力探测前置**：解析前先发一次 `Range: bytes=0-0`，
   非 206 立即给用户「该链接不支持分段，请下载到本地」——避免 GB 级无效等待。
3. **ZIP64 extra field `0x0001` 解析**：本项目 `ADR-001` 技术债 5 已点名
   （recovery 包 `super.img` 常 5~6 GB，必现 `0xFFFFFFFF`），VioletToolBox 的解析顺序
   （uncompressedSize → compressedSize → localHeaderOffset，按占位符出现顺序消费）
   可直接照搬。
4. **raw deflate**：zip 条目 DEFLATE 无 zlib 头，必须 `Inflater(true)`
   （`ADR-001` 技术债 6，VioletToolBox 用 `DeflateStream` 处理同类问题）。
5. **惰性落盘 + 软上限**：先内存缓存解析所需前缀，越过阈值才落临时文件；
   对应本项目 10 MB 红线（ADR-001 D3）。
6. **错误文案差异化**：按失败原因（不支持 Range / 无 Content-Length / 非 206 /
   没有 payload.bin / magic 错误）分别给可操作提示，本项目已有
   `NOT_A_PAYLOAD_ZIP` / `NOT_A_VALID_ZIP` 标记机制，可扩充。
7. **流量可观测**：`BytesRead` 累加 → UI 显示实际下载量与速度，
   让用户确信「没在下整包」。

## 5. 参考代码索引（VioletToolBox）

| 关注点 | 位置 |
|--------|------|
| Reader 抽象 / HTTP Range / 块缓存 / 重试 | `Payload_Dumper_C#/Core/RandomAccessReaders.cs` |
| ZIP EOCD / ZIP64 / 中央目录 / 条目提取 | `Payload_Dumper_C#/Core/ZipStoredEntryLocator.cs` |
| 源选择、payload 定位策略、CrAU+manifest 解析 | `Payload_Dumper_C#/Core/PayloadProcessing.cs` |
| URL 预处理、读取信息主流程、通用 ZIP 分支、导出 | `MainWindow.Payload.cs` |
