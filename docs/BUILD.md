# 构建指南与踩坑记录（Build Notes）

本文档记录 VIVO-OTA-Tracker-Android 的构建方式与本机实测遇到的坑。

## 1. 项目结构要点

- KMP（Kotlin Multiplatform）项目，`app` 为共享代码，`app/android` 为 Android 应用模块。
- 版本相关配置集中在 `buildSrc/src/main/kotlin/ProjectConfig.kt`。
- Android 模块构建脚本：`app/android/build.gradle.kts`。

## 2. 常用构建命令

```bash
# 构建 Debug APK（无需签名）
./gradlew :app:android:assembleDebug

# 构建 Release APK（需先配置签名，否则会失败）
./gradlew :app:android:assembleRelease

# 清理后重新构建（修复增量缓存损坏时必用）
./gradlew :app:android:clean :app:android:assembleDebug
```

构建产物位置：
- Debug：`app/android/build/outputs/apk/debug/vivo OTA Tracker-vX.Y.Z(N)-debug.apk`
- Release：`app/android/build/outputs/apk/release/...`

## 3. 踩坑记录

### 坑 1：`BuildConfig` 报 "Unresolved reference"
- **现象**：在 `androidMain` 业务代码里引用 `BuildConfig.*` 编译失败。
- **原因**：AGP 8+ 默认关闭了 `buildConfig` 生成特性。
- **解决**：在 `app/android/build.gradle.kts` 的 `android {}` 块中开启：
  ```kotlin
  buildFeatures { buildConfig = true }
  ```

### 坑 2：改了 `build.gradle.kts` 后报 `No file known for: classes.dex`
- **现象**：`packageDebug` 阶段失败，`No file known for: classes.dex`。
- **原因**：修改 `build.gradle.kts`（如开关 buildFeatures）使配置缓存/增量 dex 状态不一致。
- **解决**：执行 `clean` 后重新构建即可：
  ```bash
  ./gradlew :app:android:clean :app:android:assembleDebug
  ```

### 坑 3：配置缓存与 build.gradle 改动冲突
- **现象**：改 `build.gradle.kts` 后偶发缓存相关异常。
- **缓解**：改动构建脚本时附加 `--no-configuration-cache`：
  ```bash
  ./gradlew :app:android:assembleDebug --no-configuration-cache
  ```

### 坑 4：业务代码不能直接引用 `buildSrc` 对象
- **现象**：`import ProjectConfig` 报 "Unresolved reference"。
- **原因**：`buildSrc` 只在 Gradle 配置阶段可见，不对应用运行时代码可见。
- **解决**：需要把构建期常量传给业务代码时，用 `buildConfigField(...)` 注入到 `BuildConfig`，
  而非直接 import。

## 4. 本机实测结果

- 环境：Windows / PowerShell 7 / Gradle 9.6.1（通过 `./gradlew`）。
- 改动 `ProjectConfig.VERSION_NAME = "1.4.0"` 后，
  `installDebug` 构建成功，产物：
  `vivo OTA Tracker-v1.4.0(12)-debug.apk`（versionCode=12，对应 git commit 数）。
- 一次完整 Debug 构建耗时约 3-4 分钟（首次/clean 后）。

## 5. 全新环境搭建踩坑（Windows 实测）

本机从零搭建时遇到的一系列环境类问题，按顺序解决后构建成功。

### 环境要求
- **JDK 25+**（项目 `mise.toml` 指定 `gradle = "9.6.1"`，Gradle daemon 需 JDK 25 运行）。
- **Android SDK**：`compileSdk 37`（实际平台包为 `platforms;android-37.0`）、
  `build-tools;37.0.0`、`platform-tools`。
- 系统自带 Java 11 不够，需用 `mise install java@25` 安装 JDK 25。

### 坑 5：Android SDK 组件版本号不是整数
- **现象**：`sdkmanager "platforms;android-37"` 报 `Failed to find package`。
- **原因**：API 37 的平台包在仓库里命名为 `platforms;android-37.0`（带 minor 版本）。
- **解决**：安装 `platforms;android-37.0`、`build-tools;37.0.0`、`platform-tools`。

### 坑 6：sdkmanager 直链文件名易错
- `build-tools;37.0.0` 对应的官方直链 zip 是
  **`build-tools_r37_windows.zip`**（注意是 `r37_windows`，下划线分隔，没有 `.0.0`）。
  之前写成 `build-tools_r37-windows.zip` 会 404。
- 平台包直链：`platform-37.0_r02.zip`；platform-tools：`platform-tools-latest-windows.zip`。
- 国内镜像（清华大学）：`https://mirrors.tuna.tsinghua.edu.cn/Android/repository/`。

### 坑 7：sdkmanager 在非 TTY 下不读 stdin 的 `y`
- **现象**：`echo y | sdkmanager --licenses`、管道或 `Start-Process` 喂 `y` 都无法接受许可证，
  构建报 `License for package Android SDK Platform 37.0 not accepted`。
- **解决**：直接在 `Android SDK 根目录/licenses/` 下写入两个固定 hash 文件（等价于交互点 y）：
  - `android-sdk-license` → `24333f8a63b6825ea9c5514f83c2829b004d1fee`
  - `android-sdk-preview-license` → `84831b9409646a918e30573bab4c9c91346d8abd`
  - 用 ASCII 无 BOM、无换行写入（`Set-Content -NoNewline -Encoding ascii`）。

### 坑 8：Gradle 找不到 SDK 位置
- **解决**：项目根目录 `local.properties` 写入 `sdk.dir=F:\\Android`，
  或设置环境变量 `ANDROID_HOME=F:\Android`。

### 坑 9：仓库源连不上（先确认是不是断网！）
- **现象**：`settings.gradle.kts` 默认配阿里云镜像（`maven.aliyun.com`）。构建报
  `Could not GET 'https://maven.aliyun.com/...' -> 不知道这样的主机。`
- **重要**：本机实测阿里云镜像**本身可用**，该报错绝大多数是**电脑断网 / VPN 断开**导致，
  不要因此盲目改仓库源。先检查网络再决定。
- **注意**：Gradle 在某次会话中某仓库失败后会在本次会话**禁用该仓库**（日志 `Repository X is disabled due to earlier error`），
  因此改完源或恢复网络后要加 `--refresh-dependencies` 或先 `./gradlew --stop` 杀掉 daemon 再构建。

### 坑 10：Gradle daemon 缓存了错误的仓库/SDK 状态
- 修改 `settings.gradle.kts` 或 SDK 目录后，建议先停 daemon 再构建：
  ```bash
  ./gradlew --stop
  ./gradlew :app:android:assembleDebug --no-configuration-cache --refresh-dependencies
  ```

### 总结：从零到构建成功的最小步骤
1. `mise install java@25`（或确保 JDK 25 在 PATH）。
2. 下载 Android commandline-tools 到 `F:\Android\cmdline-tools\latest`，
   用 JDK 25 的 java 运行 sdkmanager 安装 `platforms;android-37.0`、`build-tools;37.0.0`、`platform-tools`
   （注意 sdkmanager.bat 需 `JAVA_HOME` 指向 JDK 25，否则回退系统 Java 报 class version 错）。
3. 写 `F:\Android\licenses/` 下两个 license 文件。
4. 项目根 `local.properties` 写 `sdk.dir=F:\\Android`。
5. `settings.gradle.kts` 用阿里云镜像（`maven.aliyun.com`，本机实测可用，断网才会连不上）。
6. `mise exec -- ./gradlew.bat :app:android:assembleDebug --no-configuration-cache` 构建。

## 6. 升级版本的标准流程

1. 改 `buildSrc/src/main/kotlin/ProjectConfig.kt` 的 `VERSION_NAME`（当前 `1.4.0`）。
2. （可选）`git commit` 会让 versionCode 自动 +1。
3. `./gradlew :app:android:clean :app:android:assembleDebug` 重新构建。
4. 应用内关于页会自动显示新版本（通过 `BuildConfig.APP_VERSION_NAME`）。

## 7. 生成发布签名密钥（release keystore）

正式版 APK 必须签名才能装到非 root 设备。项目 `app/android/build.gradle.kts`
已写好签名读取逻辑：优先读 `local.properties` 的 `KEYSTORE_PATH/KEYSTORE_PASS/KEY_ALIAS/KEY_PASSWORD`，
其次读同名环境变量。

### 生成 keystore
用 JDK 自带 `keytool`（需 JDK 25，已在 PATH 或 `mise` 环境）：
```bash
keytool -genkeypair -v ^
  -keystore "F:\learn-front\learn-hook\VIVO-OTA-Tracker-Android\release-key.jks" ^
  -alias vivo_ota_tracker -keyalg RSA -keysize 2048 -validity 10000 ^
  -storepass "VivoOta@2026" -keypass "VivoOta@2026" ^
  -dname "CN=VivoOtaTracker, OU=Dev, O=MyTiAnTian, L=Unknown, ST=Unknown, C=CN"
```
- 有效期 10000 天（约 27 年），RSA 2048。
- 本机实测生成成功，文件：`release-key.jks`，别名 `vivo_ota_tracker`。

### 写入 local.properties
```properties
KEYSTORE_PATH=F:\learn-front\learn-hook\VIVO-OTA-Tracker-Android\release-key.jks
KEYSTORE_PASS=VivoOta@2026
KEY_ALIAS=vivo_ota_tracker
KEY_PASSWORD=VivoOta@2026
```
之后 `build.gradle.kts` 会自动据此创建 `signingConfigs["release"]`（启用 V2+V3 签名），
并挂到 `buildTypes.release` / `buildTypes.debug` 上。

### 安全提醒（非常重要）
- **`release-key.jks` 必须永久备份**（网盘 / 密码管理器），丢了就无法再更新已发布的 app。
- `.gitignore` 已忽略 `local.properties`、`*.jks`、`*.keystore`，密钥**不会被提交进 git**，
  确认无需额外处理。
- 本机 `release-key.jks` 口令为 `VivoOta@2026`，属敏感信息，勿明文外传。

## 8. 构建正式版（release）

### 流程
1. 确保已生成 keystore 并写入 `local.properties`（见第 7 节）。
2. 确认 `buildSrc/.../ProjectConfig.kt` 的 `VERSION_NAME` 是要发的版本。
3. 双击 `build-release.bat`，或命令行：
   ```bash
   .\gradlew.bat :app:android:clean :app:android:assembleRelease --no-configuration-cache
   ```
4. 产物：`app/android/build/outputs/apk/release/vivo OTA Tracker-vX.Y.Z(N)-release.apk`

### 实测结果
- 本机 `BUILD SUCCESSFUL in 3m 20s`，`versionCode=9`（git commit 数，release 示例）。
- 同配置打 debug：`BUILD SUCCESSFUL in 3m 47s`，产物 `vivo OTA Tracker-v1.4.0(12)-debug.apk`。
- release 开启 `minifyEnabled=true` + `shrinkResources=true`（R8 混淆 + 资源压缩），
  比 debug 慢（约 3-5 分钟）。
- 构建日志关键任务全跑通：`validateSigningRelease`、`writeReleaseSigningConfigVersions`、
  `lintVitalRelease`、`minifyReleaseWithR8`、`packageRelease`。

### 坑 11：PowerShell 下 `&` 会把构建放后台，日志被占用
- **现象**：用 `gradlew ... > log.txt & echo EXIT` 时，PowerShell 的 `&` 是**后台作业**，
  主进程立刻返回，且日志文件被后台 job 持续占用，后续 `Remove-Item` / 重跑会报
  `being used by another process`。
- **解决**：在 PowerShell 里用 `Start-Process -RedirectStandardOutput log.txt -Wait` 同步等待；
  或先用 `Get-Job | Stop-Job` 清理残留后台作业再操作。
- **不要**用 `cd /d` + `&&` 的 cmd 语法（PowerShell 不认 `cd /d`），改用 `Set-Location`。

### 坑 12：断网导致 lint 工具下载失败（误判为仓库问题）
- **现象**：`lintVitalAnalyzeRelease` 报 `Could not download intellij-core-32.2.1.jar from maven.aliyun.com`。
- **根因**：当时电脑断网，并非阿里云镜像不可用。恢复网络后同配置构建成功。
- **教训**：先确认网络，再决定是否改 `settings.gradle.kts` 仓库源（本项目用阿里云镜像正常）。
- 临时绕过（不推荐，会失去 lint 检查）：在 `app/android/build.gradle.kts` 的
  `android { lint { checkReleaseBuilds = false; abortOnError = false } }`。

### 坑 13：release 版卡在"正在初始化加密引擎"
- **现象**：debug 包正常，release 包安装后一直显示"正在初始化加密引擎"
  （文案来自 `VivoApp.kt:251` 的 `crypto_init`，对应 `VivoCrypto.init()` 未完成 / `ready=false`）。
- **根因**：R8 混淆 + 资源压缩会把 `com.vivo.seckeysdk.**`（native 加密引擎 `libvivoseckey.so`
  的 JNI 封装层 `SDKCipherNative` / `NativeRequest` / `NativeResponse` 等）重命名或裁剪，
  `SDKCipherNative.init()` 返回 `false`，UI 永远停在初始化。
- **解决**：在 `app/android/proguard-rules-android.pro` 增加保留规则（已加）：
  ```proguard
  -keep class com.vivo.seckeysdk.** { *; }
  -keep class com.vivo.seckeysdk.utils.** { *; }
  -dontwarn com.vivo.seckeysdk.**
  ```
  （`io.github.long36708.updater.**` 本就在 `-keep` 里，无需再补。）
- **验证方式**：本机重打 release 包 `BUILD SUCCESSFUL in 3m 35s`，`libvivoseckey.so` 仍正常打包；
  **UI 是否真的不卡需在真机（vivo 设备）实测**（`adb logcat | grep VivoCrypto` 看
  `Native init() => false` / `Using remote crypto server` 进一步定位）。
- **注意**：`Unable to strip the following libraries: libvivoseckey.so` 是无害提示
  （NDK 缺 strip 工具），不影响运行。
