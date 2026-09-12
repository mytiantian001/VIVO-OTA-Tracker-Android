# 机型默认软件版本号填充 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `vivo_devices.json` 中的机型可携带默认软件版本号，选中机型时自动带入查询条件，同时不覆盖用户已手动输入的值，并提供显式刷回入口。

**Architecture:** 机型表新增可选字段 `default_sw_version`，由 `VivoDeviceDatabase` 用 `optString` 兜底解析。ViewModel 侧引入脏标记 `isSwVersionCustom` 区分「用户手输」与「自动填充」，并将现有 `updateSoftwareVersion()` 拆为 `applySwVersion`（纯解析写入）/ `fillSwVersion`（清脏）/ `updateSoftwareVersion`（置脏）三层，确保填充时 `androidVersion` 同步联动。UI 在版本号输入框下方提供「用推荐版本」刷回按钮。

**Tech Stack:** Kotlin、Jetpack Compose、Miuix 组件库、Android ViewModel + StateFlow、org.json

**设计依据：** `docs/ADR-003-device-default-sw-version.md`（已批准，实施前请先通读）

---

## 提交策略

> **本计划默认不自动 git commit。** 每个 Task 末尾的 Commit 步骤只有在用户明确授权后才执行；未授权时跳过该步骤，继续下一个 Task，所有改动保留在工作区。

## 测试策略说明

本项目**没有 JVM 单元测试基建**（全仓 0 个 `*Test*.kt`，无 `src/test` 目录）。因此不引入 JUnit/Robolectric 基建（超出本需求范围，YAGNI），沿用项目既有惯例：

- **同构验证脚本**：根目录 `verify_adr003_sw_version.py`，与 `verify_adr002_ui_logic.py` 同一模式，把纯逻辑用 Python 重写后断言。用于锁住解析矩阵与状态机，防回归。
- **编译验证**：`gradlew.bat :app:android:assembleDebug --no-configuration-cache`
- **冒烟验证**：真机手动验证清单（Task 6 给出）

## 文件清单

| 文件 | 改动 |
|------|------|
| `app/android/src/main/kotlin/.../vivo/VivoDeviceDatabase.kt` | `VivoDevice` 加字段 + `optString` 解析 |
| `app/android/src/main/kotlin/.../vivo/VivoModels.kt` | `VivoOtaUiState` 加 `isSwVersionCustom` |
| `app/android/src/main/kotlin/.../vivo/VivoOtaViewModel.kt` | 拆函数、改 3 个触发点、加刷回接口、改历史回填 |
| `app/android/src/main/res/values/strings.xml` | 加 `sw_version_use_recommended` |
| `app/android/src/main/res/values-zh-rCN/strings.xml` | 加 `sw_version_use_recommended` |
| `app/android/src/main/kotlin/.../vivo/VivoApp.kt` | `VersionInputCard` 加刷回按钮 |
| `verify_adr003_sw_version.py`（新建） | 同构逻辑验证 |
| `app/android/src/main/assets/vivo_devices.json` | 填充 `default_sw_version`（**Task 7，阻塞待确认**） |

---

### Task 1: 数据层 — `VivoDevice` 与 JSON 解析

**Files:**
- Modify: `app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoDeviceDatabase.kt:6-37`

- [ ] **Step 1: 给 `VivoDevice` 加字段**

将 6-10 行的 data class 改为：

```kotlin
data class VivoDevice(
    val model: String,
    val codename: String,
    val model_sw_ver: String,
    // ADR-003 D1：机型默认软件版本号。空串 = 该机型未配置，不做任何填充。
    // 注意与 model_sw_ver 区分：后者是硬件公开型号（V2419A），
    // 本字段是系统软件版本号（15.0.33.7.W10）。
    val defaultSwVersion: String = ""
)
```

- [ ] **Step 2: 解析改用 `optString`**

将 28-32 行的构造改为：

```kotlin
                devices.add(VivoDevice(
                    model = obj.getString("model"),
                    codename = obj.getString("codename"),
                    model_sw_ver = obj.getString("model_sw_ver"),
                    // ADR-003 D1：可选字段，缺失即空串。
                    // 必须用 optString——getString 对缺字段会抛 JSONException，
                    // 而 load() 无 try-catch，会导致整个机型库加载失败。
                    defaultSwVersion = obj.optString("default_sw_version", "")
                ))
```

- [ ] **Step 3: 确认改动**

读取 `VivoDeviceDatabase.kt` 全文，确认字段与解析均已就位且仍是有效 Kotlin。

- [ ] **Step 4: Commit（需用户授权）**

```bash
git add app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoDeviceDatabase.kt
git commit -m "feat: VivoDevice 支持可选的 default_sw_version 字段"
```

---

### Task 2: 状态 — `VivoOtaUiState` 加脏标记

**Files:**
- Modify: `app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoModels.kt:47`

- [ ] **Step 1: 加 `isSwVersionCustom`**

在 `VivoOtaUiState` 的 `softwareVersion` 之后插入：

```kotlin
    val softwareVersion: String = "15.0.33.7.W10",
    // ADR-003 D2：false = 版本号仍处于「自动跟随机型」状态，切机型时可被覆盖；
    //            true  = 用户手动编辑过，此后切机型一律不覆盖。
    val isSwVersionCustom: Boolean = false,
```

- [ ] **Step 2: 确认字段位置**

读取 `VivoModels.kt:40-55`，确认字段插在 `softwareVersion` 之后、`androidVersion` 之前。

- [ ] **Step 3: Commit（需用户授权）**

```bash
git add app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoModels.kt
git commit -m "feat: VivoOtaUiState 增加 isSwVersionCustom 脏标记"
```

---

### Task 3: ViewModel — 拆分解析、改造触发点、加刷回接口

**Files:**
- Modify: `app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoOtaViewModel.kt`

> 本 Task 六处改动同属一个逻辑单元（版本号填充状态机），合成一个任务以保证每个 commit 自洽。

- [ ] **Step 1: 拆分 `updateSoftwareVersion`**

将 103-118 行的 `updateSoftwareVersion` 整体替换为下面三个函数：

```kotlin
    /**
     * 只做「解析 + 写入」，不碰脏标记（ADR-003 D3）。
     *
     * 自动填充与用户手输都必须走这里：softwareVersion 点号前的数字决定 androidVersion，
     * 绕过本函数直接 copy(softwareVersion =) 会让 Android 16 机型填完后 androidVersion
     * 仍停在 15，查询直接查错。
     */
    private fun applySwVersion(v: String) {
        val majorVersion = if (v.contains('.')) {
            v.substringBefore('.').toIntOrNull()
        } else {
            v.toIntOrNull()?.takeIf { it in 13..16 }
        }
        if (majorVersion != null && majorVersion > 0) {
            if (majorVersion in 13..16) {
                _uiState.update { it.copy(softwareVersion = v, androidVersion = majorVersion, isCustomAndroidVersion = false) }
            } else {
                _uiState.update { it.copy(softwareVersion = v, androidVersion = majorVersion, isCustomAndroidVersion = true, customAndroidVersion = majorVersion.toString()) }
            }
        } else {
            _uiState.update { it.copy(softwareVersion = v) }
        }
    }

    /** 用户手动输入 → 置脏，之后切机型不再覆盖（ADR-003 D4）。 */
    fun updateSoftwareVersion(v: String) {
        applySwVersion(v)
        _uiState.update { it.copy(isSwVersionCustom = true) }
    }

    /** 自动填充 / 刷回 → 清脏，恢复「自动跟随机型」状态（ADR-003 D4）。 */
    private fun fillSwVersion(v: String) {
        applySwVersion(v)
        _uiState.update { it.copy(isSwVersionCustom = false) }
    }
```

> 解析逻辑与原有实现**逐字一致**，包括「不含点号的 `17` 不解析、含点号的 `17.0` 才解析」这一既有行为——本任务只做拆分，不改变语义。

- [ ] **Step 2: 新增刷回接口**

紧接上一步的三个函数之后插入：

```kotlin
    /**
     * 「用推荐版本」按钮：用当前选中机型的推荐版本号刷新输入框，并恢复自动跟随。
     * 手动模式或该机型未配置时静默不作为（ADR-003 D5）。
     */
    fun applyRecommendedSwVersion() {
        val state = _uiState.value
        if (state.manualMode) return
        val device = VivoDeviceDatabase.devicesOf(state.selectedSeries)
            .getOrNull(state.selectedModelIndex) ?: return
        if (device.defaultSwVersion.isBlank()) return
        fillSwVersion(device.defaultSwVersion)
    }
```

- [ ] **Step 3: 改造 `applyDefaultSelection`**

在 48-59 行的 `_uiState.update { ... }` 调用之后追加启动填充：

```kotlin
        // ADR-003 D4：启动时尚未手动编辑过，若默认机型配置了推荐版本号则带入
        if (device.defaultSwVersion.isNotBlank()) {
            fillSwVersion(device.defaultSwVersion)
        }
```

- [ ] **Step 4: 改造 `selectSeries`**

在 `selectSeries` 的 `_uiState.update { ... }` 之后追加：

```kotlin
        // ADR-003 D4：未手动编辑过版本号时，跟随新系列首个机型的推荐值
        val recommended = first?.defaultSwVersion.orEmpty()
        if (!_uiState.value.isSwVersionCustom && recommended.isNotBlank()) {
            fillSwVersion(recommended)
        }
```

- [ ] **Step 5: 改造 `selectDevice`**

在 `selectDevice` 的 `_uiState.update { ... }` 之后追加：

```kotlin
        // ADR-003 D4：未手动编辑过版本号时才跟随机型推荐值
        if (!_uiState.value.isSwVersionCustom && device.defaultSwVersion.isNotBlank()) {
            fillSwVersion(device.defaultSwVersion)
        }
```

- [ ] **Step 6: 历史回填置脏**

在 `fillHistoryBack` 的 `copy(...)` 参数列表末尾（`queryDomain = entry.queryDomain` 之后）追加：

```kotlin
                // ADR-003 D6：历史回填的版本号视为用户明确选择，后续切机型不覆盖
                isSwVersionCustom = true
```

- [ ] **Step 7: 确认改动**

读取 `VivoOtaViewModel.kt:41-140` 与 `384-405`，确认六处改动就位、无重复定义、无遗漏逗号。

- [ ] **Step 8: Commit（需用户授权）**

```bash
git add app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoOtaViewModel.kt
git commit -m "feat: 选机型时自动填充推荐软件版本号，手输后不再覆盖"
```

---

### Task 4: 字符串资源

**Files:**
- Modify: `app/android/src/main/res/values/strings.xml`
- Modify: `app/android/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: 英文默认值**

在 `values/strings.xml` 的 `hint_sw_version` 之后插入：

```xml
    <string name="sw_version_use_recommended">Use recommended</string>
```

- [ ] **Step 2: 简体中文**

在 `values-zh-rCN/strings.xml` 的 `hint_sw_version` 之后插入：

```xml
    <string name="sw_version_use_recommended">用推荐版本</string>
```

- [ ] **Step 3: 确认无重复**

两个文件中各搜索一次 `sw_version_use_recommended`，确认恰好 1 处。

- [ ] **Step 4: Commit（需用户授权）**

```bash
git add app/android/src/main/res/values/strings.xml app/android/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat: 新增「用推荐版本」字符串资源"
```

---

### Task 5: UI — `VersionInputCard` 加刷回按钮

**Files:**
- Modify: `app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoApp.kt:525-551`

> `Arrangement`、`Row`、`Alignment`、`clickable`、`Color` 已在该文件顶部导入，无需新增 import。

- [ ] **Step 1: 替换 `VersionInputCard`**

将 525-551 行整体替换为：

```kotlin
@Composable
private fun VersionInputCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    // ADR-003 D5：手动模式下机型下拉是隐藏的，不存在「选中机型」语境，不提供推荐版本。
    // 此处直接查 database 与 ModelDropdownCard 的既有写法保持一致。
    val recommended = if (state.manualMode) "" else {
        VivoDeviceDatabase.devicesOf(state.selectedSeries)
            .getOrNull(state.selectedModelIndex)?.defaultSwVersion.orEmpty()
    }
    // 三者全满足才显示：非手动模式、机型已配置、且推荐值与当前值不同
    val showRecommended = recommended.isNotBlank() &&
        recommended.trim() != state.softwareVersion.trim()
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            TextField(
                insideMargin = DpSize(16.dp, 24.dp),
                modifier = Modifier.fillMaxWidth(),
                value = state.softwareVersion,
                onValueChange = { viewModel.updateSoftwareVersion(it) },
                label = stringResource(R.string.label_sw_version),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\u26A0", fontSize = 13.sp, color = Color(0xFFFF9800))
                Text(
                    text = stringResource(R.string.hint_sw_version),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .weight(1f)
                )
                if (showRecommended) {
                    Text(
                        text = stringResource(R.string.sw_version_use_recommended),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { viewModel.applyRecommendedSwVersion() }
                            .padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 确认改动**

读取 `VivoApp.kt:524-565`，确认函数替换完整、括号闭合、`.weight(1f)` 已加。

- [ ] **Step 3: Commit（需用户授权）**

```bash
git add app/android/src/main/kotlin/io/github/long36708/updater/vivo/VivoApp.kt
git commit -m "feat: 版本号输入框增加「用推荐版本」刷回按钮"
```

---

### Task 6: 验证

**Files:**
- Create: `verify_adr003_sw_version.py`

- [ ] **Step 1: 写同构验证脚本**

新建 `verify_adr003_sw_version.py`，内容如下（解析逻辑与 Kotlin `applySwVersion` 逐条对应）：

```python
# -*- coding: utf-8 -*-
"""
验证 ADR-003 的软件版本号填充逻辑（与 Kotlin 实现同构）：

1. apply_sw_version：版本号 -> androidVersion / isCustomAndroidVersion 解析矩阵
2. 填充状态机：脏标记 is_sw_version_custom 的流转与覆盖时机
3. 未配置机型的「不填充」语义：保持原值，不清空

本项目无 JVM 单测基建，沿用 verify_adr002_ui_logic.py 的同构验证方式。
"""

DEFAULT_SW = "15.0.33.7.W10"


class S:
    """VivoOtaUiState 的最小同构。"""

    def __init__(self):
        self.software_version = DEFAULT_SW
        self.android_version = 15
        self.is_custom_android_version = False
        self.custom_android_version = ""
        self.is_sw_version_custom = False
        self.manual_mode = False
        self.selected_series = ""
        self.selected_model_index = 0


def _to_int_or_none(s):
    try:
        return int(s)
    except ValueError:
        return None


def apply_sw_version(state, v):
    """对应 Kotlin applySwVersion：只解析写入，不碰脏标记。"""
    if "." in v:
        major = _to_int_or_none(v.split(".")[0])
    else:
        n = _to_int_or_none(v)
        # Kotlin: v.toIntOrNull()?.takeIf { it in 13..16 }
        major = n if (n is not None and 13 <= n <= 16) else None
    if major is not None and major > 0:
        state.software_version = v
        state.android_version = major
        if 13 <= major <= 16:
            state.is_custom_android_version = False
        else:
            state.is_custom_android_version = True
            state.custom_android_version = str(major)
    else:
        state.software_version = v


def update_software_version(state, v):
    """用户手输 -> 置脏。"""
    apply_sw_version(state, v)
    state.is_sw_version_custom = True


def fill_sw_version(state, v):
    """自动填充 / 刷回 -> 清脏。"""
    apply_sw_version(state, v)
    state.is_sw_version_custom = False


def select_device(state, default_sw_version):
    """切机型：未脏且新机型有推荐值才填充。"""
    if not state.is_sw_version_custom and default_sw_version.strip():
        fill_sw_version(state, default_sw_version)


def _ok(cond, msg):
    if not cond:
        raise AssertionError(msg)


def test_parse_matrix():
    """解析矩阵：与 Kotlin applySwVersion 逐条对应。"""
    cases = [
        # (输入, 期望 androidVersion, 期望 isCustom, 期望 customAndroidVersion)
        ("15.0.33.7.W10", 15, False, ""),
        ("16.1.16.5.W10", 16, False, ""),
        ("17.0.1.0.W10", 17, True, "17"),
        ("12.0.1.0", 12, True, "12"),
        ("15", 15, False, ""),
        # 既有行为（非本次引入）：不含点号时受 13..16 过滤，故 "17" 不解析大版本。
        # 这是 Kotlin 原实现 v.toIntOrNull()?.takeIf { it in 13..16 } 的直接结果。
        ("17", 15, False, ""),
        # 非数字开头：只改 softwareVersion，androidVersion 保持原值
        ("abc", 15, False, ""),
    ]
    for raw, exp_android, exp_custom, exp_custom_str in cases:
        s = S()
        apply_sw_version(s, raw)
        _ok(s.software_version == raw,
            f"{raw!r}: softwareVersion 应为 {raw!r}，实为 {s.software_version!r}")
        _ok(s.android_version == exp_android,
            f"{raw!r}: androidVersion 应为 {exp_android}，实为 {s.android_version}")
        _ok(s.is_custom_android_version == exp_custom,
            f"{raw!r}: isCustomAndroidVersion 应为 {exp_custom}，实为 {s.is_custom_android_version}")
        _ok(s.custom_android_version == exp_custom_str,
            f"{raw!r}: customAndroidVersion 应为 {exp_custom_str!r}，实为 {s.custom_android_version!r}")


def test_fill_syncs_android_version():
    """核心回归点：填充必须同步 androidVersion，否则 16 的机型会按 15 查。"""
    s = S()
    fill_sw_version(s, "16.1.16.5.W10")
    _ok(s.software_version == "16.1.16.5.W10", "填充后 softwareVersion 应更新")
    _ok(s.android_version == 16,
        f"填充 16.x 后 androidVersion 应为 16，实为 {s.android_version}")
    _ok(s.is_sw_version_custom is False, "自动填充后脏标记应为 False")


def test_manual_edit_sets_dirty_and_blocks_overwrite():
    """手输置脏 -> 切机型不再覆盖。"""
    s = S()
    fill_sw_version(s, "16.1.16.5.W10")
    update_software_version(s, "手动改的版本")
    _ok(s.is_sw_version_custom is True, "手输后脏标记应为 True")

    select_device(s, "15.0.1.0.W10")
    _ok(s.software_version == "手动改的版本",
        f"脏标记置位后切机型不应覆盖，实为 {s.software_version!r}")


def test_apply_recommended_clears_dirty():
    """点「用推荐版本」应刷新取值并恢复自动跟随。"""
    s = S()
    update_software_version(s, "手动改的版本")
    _ok(s.is_sw_version_custom is True, "前置条件：脏标记应为 True")

    # 模拟 applyRecommendedSwVersion（manualMode=False、机型已配置）
    fill_sw_version(s, "15.0.1.0.W10")
    _ok(s.software_version == "15.0.1.0.W10", "刷回应写入推荐值")
    _ok(s.is_sw_version_custom is False, "刷回后脏标记应清零")

    # 恢复自动跟随：之后切机型会继续自动填充
    select_device(s, "16.2.3.4.W10")
    _ok(s.software_version == "16.2.3.4.W10",
        f"刷回后应恢复自动填充，实为 {s.software_version!r}")
    _ok(s.android_version == 16, "自动填充应同步 androidVersion")


def test_unconfigured_device_keeps_value():
    """未配置推荐版本的机型：保持原值，不清空（ADR-003 边界情况表）。"""
    s = S()
    fill_sw_version(s, "16.2.3.4.W10")
    select_device(s, "")  # 该机型无 default_sw_version
    _ok(s.software_version == "16.2.3.4.W10",
        f"未配置机型应保持原值不清空，实为 {s.software_version!r}")


def test_unconfigured_device_keeps_dirty_value():
    """脏标记置位后切到未配置机型：仍保持用户手输值。"""
    s = S()
    update_software_version(s, "手动值")
    select_device(s, "")
    _ok(s.software_version == "手动值", "应保持用户手输值")


if __name__ == "__main__":
    tests = [
        test_parse_matrix,
        test_fill_syncs_android_version,
        test_manual_edit_sets_dirty_and_blocks_overwrite,
        test_apply_recommended_clears_dirty,
        test_unconfigured_device_keeps_value,
        test_unconfigured_device_keeps_dirty_value,
    ]
    for t in tests:
        t()
        print(f"[OK] {t.__name__}")
    print(f"\nAll {len(tests)} checks passed.")
```

- [ ] **Step 2: 跑验证脚本**

```bash
python verify_adr003_sw_version.py
```

预期输出 6 行 `[OK] ...` 与 `All 6 checks passed.`。若失败，按断言提示定位是脚本还是 Kotlin 实现不同构——**修正时以 Kotlin 实现为准**。

- [ ] **Step 3: 编译验证**

```bash
gradlew.bat :app:android:assembleDebug --no-configuration-cache
```

预期 `BUILD SUCCESSFUL`。此步同时验证 Task 1-5 全部 Kotlin/XML 改动无编译错误、字符串资源无缺失。

- [ ] **Step 4: 冒烟验证清单（真机）**

1. 打开 App，确认默认机型（X 系列 / vivo X200 Pro mini）的版本号输入框有值
2. 切到「Y 系列」，若该系列首个机型未配置 → 版本号**保持上一步的值不变**（不清空）
3. 手动把版本号改成任意值，再切系列/切机型 → 版本号**保持不变**（脏标记生效）
4. 找一个配置了 `default_sw_version` 的机型切过去 → 输入框**未出现**「用推荐版本」时说明值已相等
5. 手动改版本号后，确认输入框右下角出现「用推荐版本」，点击 → 恢复为该机型推荐值，且 Android 版本下拉同步变化
6. 开启「手动输入设备信息」→ 确认「用推荐版本」按钮消失
7. 从历史记录一键回填 → 确认版本号被回填，且随后切机型不会被覆盖

- [ ] **Step 5: Commit（需用户授权）**

```bash
git add verify_adr003_sw_version.py
git commit -m "test: 新增 ADR-003 版本号填充逻辑的同构验证脚本"
```

---

### Task 7: JSON 填充（**阻塞 — 待用户确认**）

**Files:**
- Modify: `app/android/src/main/assets/vivo_devices.json`

> **前置依赖：** 用户需确认真机「设置 → 关于手机 → 版本号」的实际值。
> 见 ADR-003「数据疑点」一节：`vivo_devices.json` 中「vivo X200 Pro mini」当前配的是
> `PD2419` / `V2419A`，但既有固件分析记录显示 `PD2419`/`V2419A` 对应的是 **X200 Pro（非 mini）**，
> `PD2415`/`V2415A` 才是 mini。**该 codename 疑点未澄清前，不得批量填充。**

- [ ] **Step 1: 确认 X200 Pro mini 的真实版本号**（用户提供的真机值）

- [ ] **Step 2: 填充该机型**

在「vivo X200 Pro mini」条目中加入 `default_sw_version`：

```json
    {
      "model": "vivo X200 Pro mini",
      "codename": "PD2419",
      "model_sw_ver": "V2419A",
      "default_sw_version": "<用户确认的真机版本号>"
    }
```

- [ ] **Step 3: 校验 JSON 合法性**

```bash
python -c "import json,io; d=json.load(io.open('app/android/src/main/assets/vivo_devices.json',encoding='utf-8')); print('series:', len(d)); print('total devices:', sum(len(v) for v in d.values()))"
```

预期输出系列数与机型总数，无 `JSONDecodeError`。

- [ ] **Step 4: 重跑验证**

重跑 Task 6 Step 2-3（验证脚本 + 编译），确认 JSON 改动未破坏加载。

- [ ] **Step 5: 冒烟验证**

真机打开 App → 确认默认机型的版本号输入框自动带入 Step 2 填入的值，且 Android 版本下拉同步。

- [ ] **Step 6: Commit（需用户授权）**

```bash
git add app/android/src/main/assets/vivo_devices.json
git commit -m "feat: 为 vivo X200 Pro mini 配置默认软件版本号"
```

---

## 自检结果

- **Spec 覆盖**：ADR-003 的 D1~D6 逐条对应 Task 1/2/3/3/5/3，边界情况表 6 行由 Task 6 的 4 个状态机用例 + 2 条冒烟项覆盖。无遗漏。
- **占位符扫描**：仅 Task 7 Step 2 含 `<用户确认的真机版本号>`，这是**有意的阻塞标记**并已注明前置依赖，非遗漏。
- **类型一致性**：`defaultSwVersion`（Task 1）、`isSwVersionCustom`（Task 2）、`applySwVersion` / `fillSwVersion` / `applyRecommendedSwVersion`（Task 3）、`R.string.sw_version_use_recommended`（Task 4/5）命名全程一致。
