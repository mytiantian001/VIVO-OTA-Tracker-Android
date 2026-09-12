# 术语表（Glossary）

本文件确立项目内的统一用语。文档中、以及新增代码的命名与注释应遵循此表，
避免「解压」「提取」「浏览」等词在不同语境下混用。

## OTA 包结构相关

| 术语 | 英文 / 代码对应 | 含义 |
|------|----------------|------|
| **OTA 包** | OTA package | 用户下载到的那个 `.zip` 文件整体。实测体积 4.3 ~ 11.6 GB。 |
| **zip 容器** | zip container | 上述 `.zip` 作为容器这一层，与内部载荷相对。 |
| **条目** | zip entry | zip 容器内的一个文件项，由 central directory 中的一条记录描述。 |
| **central directory** | — | zip 尾部的条目索引区。读取它即可列出全部条目，**无需下载文件内容**。 |
| **local file header** | — | 每个条目数据区前的小头，含文件名长、extra 长，**是求条目数据偏移的关键**。 |
| **ZIP64** | — | 扩展格式。当文件 >4 GB 或条目数 >65535 时启用，大小字段置 `0xFFFFFFFF`，真值在 extra field (0x0001)。 |
| **extra field (0x0001)** | — | ZIP64 扩展字段，承载真实的大小与偏移。recovery 包的 `super.img`（5~6 GB）会用到。 |
| **STORED** | method = 0 | 条目未压缩。支持 `Range` 随机读，可直接取任意片段。 |
| **DEFLATE** | method = 8 | 条目已压缩。**raw deflate，无 zlib 头**，必须用 `Inflater(true)` 解析。虽不能随机读，但因条目独立压缩，可从条目起点顺序 inflate，无需下载整个包。 |

## payload 相关

| 术语 | 英文 / 代码对应 | 含义 |
|------|----------------|------|
| **payload.bin** | — | A/B 包容器内的唯一大载荷，CrAU 格式（魔数 `43 72 41 55`）。 |
| **CrAU** | — | payload.bin 的文件头魔数。 |
| **DeltaArchiveManifest** | `update_metadata.proto` | payload.bin 内的 protobuf 清单，描述所有分区及其安装操作。 |
| **分区镜像** | partition image / `.img` | 从 payload.bin 解出的 `system.img`、`vendor.img` 等。 |

## 操作分层（关键区分）

本项目的核心动作分为四层，**术语不可混用**：

| 层 | 术语 | 英文 / 代码对应 | 输入 → 输出 |
|----|------|----------------|------------|
| **L0** | **列条目** | list entries | zip central directory → 条目列表。**不下载数据**。 |
| **L1** | **提取** | extract | payload.bin → 分区镜像 `.img` 落盘。 |
| **L2** | **浏览** | browse | 分区镜像 `.img` → 内部文件树。**未实现**（需自研 ext4/erofs）。 |
| **L3** | **整包解压** | — | 下载整包 → 解压。**已否决**（包体 11.6 GB）。 |

> 日常口语中的「解压」在本项目里**不是**一个有效术语——请按上表指明是 L0/L1/L2/L3 中哪一层。

## 新增能力相关（ADR-001）

| 术语 | 英文 / 代码对应 | 含义 |
|------|----------------|------|
| **预览** | preview | 不落盘，直接展示条目内容的前 8 KB 文本。STORED 直读，DEFLATE 流式 inflate 后主动中止。 |
| **下载** | download | 把单个条目落盘到 MediaStore.Downloads。受 10 MB 红线约束。 |
| **10 MB 红线** | — | 下载阈值。`compressedSize` 与 `uncompressedSize` **任一**超过 10 MB 即禁止下载；解压时另设实时产出校验防线防 zip bomb。 |
| **复制全部** | copy all | 把预览文本整段写入剪贴板。受 Binder 1 MB 上限约束，实际阈值 128 KB。 |
| **内容嗅探** | content sniffing | 不看扩展名，按字节分布（可打印字符比例、NUL 字节）判断是否为文本。 |
| **NUL 硬拦截** | — | 无论是否在白名单，检出 NUL 字节一律拒绝预览。防 `.pb` 等二进制被误判。 |

## 易混淆点

- **「提取」≠「解压」**：提取是 payload.bin → `.img`（L1），解压在本项目语境下无定义。
- **「浏览」≠「列条目」**：浏览是看 `.img` 内部文件树（L2，未实现），列条目是看 zip 容器条目（L0）。
- **复制上限（128 KB）远小于下载上限（10 MB）**：前者受 Binder IPC 限制，后者受流量/存储考量，二者无关。
