# ADR-003：机型默认软件版本号配置与自动填充

- **状态**：已接受
- **日期**：2026-09-05
- **相关**：`docs/GLOSSARY.md`

## 背景

发起一次 OTA 查询需要三组设备标识：

1. codename（`PD2419`）
2. 公开型号 `model_sw_ver`（`V2419A`）
3. **当前系统软件版本号** `softwareVersion`（`15.0.33.7.W10`）

前两者由 `assets/vivo_devices.json` 提供，选中机型时自动填充。但第三者是
`VivoOtaUiState` 中的硬编码默认值 `15.0.33.7.W10`，**切换机型时不会变化**。
用户换机型后若忘记同步改版本号，就会拿 A 机型的版本号去查 B 机型的更新，
得到空结果或错误结果，且无从判断是"真没更新"还是"版本号填错"。

本 ADR 让机型表携带各自的默认版本号，选机型时自动带入。

## 关键事实（决定方案边界）

| 事实 | 影响 |
|------|------|
| `softwareVersion` 点号前的数字决定 `androidVersion`（由 `updateSoftwareVersion()` 解析） | 填充必须复用完整解析逻辑。直接 `copy(softwareVersion = x)` 会让 Android 16 机型填完后 `androidVersion` 仍停在 15，查询直接查错 |
| `VivoDeviceDatabase.load()` 用 `getString()` 读字段，且整个 `load()` 无 try-catch | 新字段必须 `optString` 兜底。用 `getString` 会让所有未配置机型抛 `JSONException`，整个机型库加载失败 |
| JSON 中已有 `model_sw_ver` 字段 | 新字段名须与其明确区分——二者语义完全不同，见 D1 |
| 机型库数百条，逐个维护不现实 | 字段设计为**可选**，缺失即不填充，支持长期增量补全 |
| 手动模式 `manualMode` 下机型下拉隐藏 | 该模式下无"选中机型"语境，自动填充与刷回入口均无意义，应隐藏 |
| 用户会手动微调版本号做对比查询 | 自动填充不得静默覆盖用户已输入的值，见 D2 |

## 决策

### D1：新增可选字段 `default_sw_version`

`vivo_devices.json` 每个机型条目新增**可选**字符串字段：

```json
{
  "model": "vivo X200 Pro mini",
  "codename": "PD2419",
  "model_sw_ver": "V2419A",
  "default_sw_version": "15.0.33.7.W10"
}
```

- 字段名加 `default_` 前缀，用于和 `model_sw_ver` 明确区分。二者极易混淆但语义完全不同：
  `model_sw_ver` 是**硬件公开型号**（`V2419A`，进网许可证上的型号），
  `default_sw_version` 是**系统软件版本号**（`15.0.33.7.W10`，设置里"关于手机"看到的版本号）。
- **字段缺失 = 该机型无推荐版本，不做任何填充**（精确匹配，不做 codename 或系列级回退）。
  跨机型串版本号会导致查错，命中率不如正确性重要。
- `VivoDevice` 对应新增 `val defaultSwVersion: String = ""`，空串表示未配置。
- 解析**必须**用 `obj.optString("default_sw_version", "")`，理由见"关键事实"表第 2 行。

### D2：引入脏标记 `isSwVersionCustom`，用户手输后不再被覆盖

`VivoOtaUiState` 新增：

```kotlin
val isSwVersionCustom: Boolean = false
```

- `false` = 版本号仍处于「自动跟随机型」状态，切机型时可被填充。
- `true` = 用户手动编辑过，此后切机型**一律不覆盖**。

这是本方案的核心。若每次切机型都无条件覆盖，用户先调好版本号再换机型做对比查询时，
输入会被静默冲掉——这类"帮倒忙"的自动化比不做更糟。

### D3：拆分解析逻辑，区分「用户输入」与「自动填充」

现有 `updateSoftwareVersion()` 一把梭做了三件事：解析 androidVersion、写入值、
且语义上代表"用户输入"。自动填充要复用其解析，但不能被标成"用户输入"。
因此拆为三层：

```kotlin
// 内部：只做解析 + 写入，不碰脏标记
private fun applySwVersion(v: String) { /* 现有解析逻辑原样保留 */ }

// 用户手输 → 置脏，之后切机型不再覆盖
fun updateSoftwareVersion(v: String) {
    applySwVersion(v)
    _uiState.update { it.copy(isSwVersionCustom = true) }
}

// 自动填充 / 刷回 → 清脏，恢复自动跟随
private fun fillSwVersion(v: String) {
    applySwVersion(v)
    _uiState.update { it.copy(isSwVersionCustom = false) }
}
```

`applySwVersion` 必须完整同步 `androidVersion` / `isCustomAndroidVersion` /
`customAndroidVersion`，理由见"关键事实"表第 1 行。

### D4：填充触发点

| 触发 | 行为 |
|------|------|
| `applyDefaultSelection()` 启动初始化 | 默认机型有推荐值才 `fillSwVersion` |
| `selectDevice(index)` 切机型 | `!isSwVersionCustom` 且新机型有推荐值 → `fillSwVersion` |
| `selectSeries(series)` 切系列 | 同上（取该系列第一个机型） |
| `updateSoftwareVersion()` 用户手输 | 置 `isSwVersionCustom = true` |

未配置推荐版本的机型：**保持输入框原值不变，不清空**。清空会让查询按钮变灰，
且用户还得重填，比不填充更糟。

### D5：提供「用推荐版本」刷回入口

脏标记置位后自动填充即停摆，需要一个显式入口让用户恢复：

```kotlin
fun applyRecommendedSwVersion() {
    if (_uiState.value.manualMode) return
    val v = 当前选中机型的 defaultSwVersion
    if (v.isBlank()) return
    fillSwVersion(v)   // 同时清脏 → 恢复自动跟随
}
```

UI 上置于 `VersionInputCard` 中 TextField 下方，与现有 ⚠ 加密库提示同一行右侧
（`Arrangement.SpaceBetween`）。显示需**同时**满足三个条件，否则隐藏：

1. `!manualMode`（手动模式下机型下拉隐藏，不存在"选中机型"语境）
2. 当前机型 `defaultSwVersion` 非空
3. 推荐值 ≠ 当前输入框值（相等时刷回无意义；比较前对两侧均 `trim()`）

函数内保留 `manualMode` 与空值两道防御，不依赖 UI 层的显示条件来保证正确性。

新增字符串资源 `sw_version_use_recommended`（用推荐版本 / Use recommended），
同时加入 `values/` 与 `values-zh-rCN/`。

### D6：历史回填视为「自定义」

`fillHistoryBack()` 回填历史查询条件时，应置 `isSwVersionCustom = true`。
该版本号是用户明确从历史恢复的，不该在后续切机型时被冲掉。

## 边界情况

| 场景 | 行为 |
|------|------|
| 选中机型未配置 `default_sw_version` | 不填充，输入框保持原值不清空，刷回按钮隐藏 |
| 脏标记未置位，但切到的新机型未配置 | 仍保持原值不变。含义是"不写入"，而非"写入空值"——清空会让查询按钮变灰且需重填，比保留更糟 |
| 手动模式 `manualMode` 开启 | 不自动填充，刷回按钮隐藏；函数内亦有防御 |
| JSON 条目缺 `default_sw_version` | `optString` 兜底空串，不抛异常 |
| 推荐值 == 当前输入框值 | 刷回按钮隐藏 |
| 点击刷回之后 | 脏标记清零，之后切机型恢复自动填充 |
| 历史记录回填 | 置脏，后续切机型不覆盖 |

## 数据填充规范

- 字段为可选，允许长期只覆盖部分机型，未覆盖者行为退化为现状（手动输入）。
- 首批仅填充「vivo X200 Pro mini」用于验证闭环，值沿用现有硬编码默认值 `15.0.33.7.W10`。
- **该值需以真机"设置 → 关于手机 → 版本号"核对后为准**，确认为准前不得批量填充其他机型。

> **数据疑点（待核实，不在本 ADR 改动范围内）**
> `vivo_devices.json` 中「vivo X200 Pro mini」当前配置为 `PD2419` / `V2419A`。
> 但既有固件分析记录显示 `PD2419` / `V2419A` 对应的是 **vivo X200 Pro**（非 mini），
> 而 `PD2415` / `V2415A` 才是 X200 Pro mini。二者共用同一套固件，烧录后固件层属性
> 统一返回 `PD2415`。若该记录属实，则 JSON 中此条目的 codename 与公开型号可能互换了，
> 需单独核对后修正——这会直接影响该填哪个版本号。

## 落地步骤

1. `VivoDevice` 新增 `defaultSwVersion` 字段
2. `VivoDeviceDatabase.load()` 改用 `optString("default_sw_version", "")` 解析
3. `VivoOtaUiState` 新增 `isSwVersionCustom`
4. `VivoOtaViewModel`：
   - 将 `updateSoftwareVersion()` 拆为 `applySwVersion` / `updateSoftwareVersion` / `fillSwVersion`
   - 改造 `applyDefaultSelection()`、`selectSeries()`、`selectDevice()` 三个触发点
   - 新增 `applyRecommendedSwVersion()`
   - `fillHistoryBack()` 置脏
5. `VersionInputCard` 增加「用推荐版本」按钮 + 字符串资源
6. `vivo_devices.json` 为「vivo X200 Pro mini」填入 `default_sw_version` 验证闭环
