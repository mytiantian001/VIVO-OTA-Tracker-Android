package io.github.long36708.updater.vivo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.long36708.updater.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import io.github.long36708.updater.vivo.payload.PayloadDumperScreen
import io.github.long36708.updater.vivo.payload.VivoPayloadViewModel

@Composable
fun VivoApp(viewModel: VivoOtaViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    var showPayloadDumper by remember { mutableStateOf(false) }
    var changelogEntry by remember { mutableStateOf<QueryHistoryEntry?>(null) }
    val payloadViewModel: VivoPayloadViewModel = viewModel()
    val context = LocalContext.current
    val darkMode = isSystemInDarkTheme()

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val codename = if (state.manualMode) state.manualCodename else state.selectedCodename

    val colorScheme = if (darkMode) {
        darkColorScheme().copy(
            background = Color(0xFF0F0F0F),
            surfaceContainer = Color(0xFF1E1E1E),
            secondaryContainer = Color(0xFF1E1E1E)
        )
    } else {
        lightColorScheme().copy(
            background = Color(0xFFF2F2F7),
            surfaceContainer = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFFFFF)
        )
    }
    MiuixTheme(colorScheme) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                SmallTopAppBar(
                    title = stringResource(R.string.app_name),
                    navigationIcon = {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = stringResource(R.string.app_name),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { showAbout = true }
                        )
                    },
                    scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).imePadding(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { CryptoStatusCard(state.cryptoReady) }

                item { ManualModeCard(state, viewModel) }

                item {
                    AnimatedVisibility(
                        visible = state.manualMode,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ManualInputCard(state, viewModel)
                    }
                    AnimatedVisibility(
                        visible = !state.manualMode,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SeriesDropdownCard(state, viewModel)
                            val devices = if (state.selectedSeries.isNotEmpty()) VivoDeviceDatabase.devicesOf(state.selectedSeries) else emptyList()
                            AnimatedVisibility(
                                visible = devices.isNotEmpty(),
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                ModelDropdownCard(state, viewModel)
                            }
                        }
                    }
                }

                item { DeviceTypeCard(state, viewModel) }
                item { AndroidVersionCard(state, viewModel) }

                if (codename.isNotEmpty()) {
                    item { CodenameInfoCard(state) }
                }

                item { VersionInputCard(state, viewModel) }
                if (state.deviceType == "phone") {
                    item { ImeiInputCard(state, viewModel) }
                }
                item { SnInputCard(state, viewModel) }
                item { ChannelCard(state, viewModel) }
                item { PackageTypeCard(state, viewModel) }

                item { QueryButton(state, viewModel) }

                val dumpUrl = state.result?.downloadUrl.orEmpty()
                if (dumpUrl.isNotEmpty()) {
                    item {
                        Button(
                            onClick = {
                                payloadViewModel.parseFromUrl(dumpUrl)
                                showPayloadDumper = true
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        ) {
                            Text(stringResource(R.string.payload_dumper))
                        }
                    }
                }

                state.error?.let { err -> item { ErrorCard(err) } }
                state.result?.let { result ->
                    item(key = "result") {
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(result) { visible = true }
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            ResultCard(result, state.changelogContent, state.softwareVersion, state.androidVersion, state.isFullPackage)
                        }
                    }
                }
                if (state.history.isNotEmpty()) {
                    item {
                        HistoryCard(
                            state = state,
                            viewModel = viewModel,
                            payloadViewModel = payloadViewModel,
                            onViewPartitions = { url ->
                                payloadViewModel.parseFromUrl(url)
                                showPayloadDumper = true
                            },
                            onViewChangelog = { changelogEntry = it }
                        )
                    }
                }
            }
        }

        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
        if (showPayloadDumper) {
            PayloadDumperScreen(
                viewModel = payloadViewModel,
                onBack = { showPayloadDumper = false }
            )
        }
        if (changelogEntry != null) {
            ChangelogScreen(
                entry = changelogEntry!!,
                viewModel = viewModel,
                onBack = { changelogEntry = null }
            )
        }
    }
}

/**
 * 独立的更新日志页：从历史记录打开，标题自带该历史项的机型/版本号，
 * 自行管理日志加载状态，完全不触碰查询页的全局 changelogContent，
 * 避免“日志版本号”与“当前查询结果版本号”混淆。
 */
@Composable
private fun ChangelogScreen(
    entry: QueryHistoryEntry,
    viewModel: VivoOtaViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val copiedMsg = stringResource(R.string.copied)
    val title = if (entry.model.isNotEmpty()) entry.model else entry.codename
    val subtitle = if (entry.resultVersion.isNotEmpty()) entry.resultVersion else entry.swVersion
    var content by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(entry.changelogUrl) {
        loading = true
        content = withContext(Dispatchers.IO) { viewModel.getChangelog(entry.changelogUrl) }
        loading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.changelog_title),
                navigationIcon = {
                    Text(
                        text = "←",
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable { onBack() },
                        fontSize = 20.sp
                    )
                },
                scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                .padding(
                    top = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = paddingValues.calculateBottomPadding() + 32.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .verticalScroll(rememberScrollState())
        ) {
            if (title.isNotEmpty() || subtitle.isNotEmpty()) {
                Text(
                    text = "$title $subtitle".trim(),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
            if (entry.changelogUrl.isNotEmpty() && entry.changelogUrl != "(Not found)") {
                ChangelogLinkActions(
                    url = entry.changelogUrl,
                    copiedMsg = copiedMsg,
                    center = true,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.loading),
                        modifier = Modifier.padding(start = 12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                val text = content ?: stringResource(R.string.no_changelog)
                Text(
                    text = text,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("changelog", text))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }
                    )
                )
            }
        }
    }
}

/**
 * 更新日志 H5 地址操作行：复制链接 / 跳转系统浏览器打开。
 * 入参是日志数据文件地址（/data/CN.js），复制与打开前先还原成 H5 页面地址，
 * 避免用户拿到的是浏览器里只显示源码的 JS 文件。
 */
@Composable
private fun ChangelogLinkActions(
    url: String,
    copiedMsg: String,
    center: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val pageUrl = VivoOtaClient.changelogPageUrl(url)
    Row(
        modifier = (if (center) Modifier.fillMaxWidth() else Modifier).then(modifier),
        horizontalArrangement = if (center) Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.btn_copy_link),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 13.sp,
            modifier = Modifier.clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("changelogUrl", pageUrl))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            }
        )
        Text(
            text = stringResource(R.string.btn_open_in_browser),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 13.sp,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)))
            }
        )
    }
}

@Composable
private fun CryptoStatusCard(ready: Boolean) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            text = if (ready) stringResource(R.string.crypto_ready) else stringResource(R.string.crypto_init),
            color = if (ready) Color(0xFF4CAF60) else Color(0xFFFF9800),
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun ManualModeCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                state = if (state.manualMode) ToggleableState.On else ToggleableState.Off,
                onClick = { viewModel.toggleManualMode() }
            )
            Text(
                text = stringResource(R.string.manual_input),
                modifier = Modifier.padding(start = 8.dp).clickable { viewModel.toggleManualMode() }
            )
        }
    }
}

@Composable
private fun ManualInputCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            TextField(
                insideMargin = DpSize(16.dp, 24.dp),
                modifier = Modifier.fillMaxWidth(),
                value = state.manualCodename,
                onValueChange = { viewModel.updateManualCodename(it) },
                label = stringResource(R.string.label_codename),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            TextField(
                insideMargin = DpSize(16.dp, 24.dp),
                modifier = Modifier.fillMaxWidth(),
                value = state.manualModelSwVer,
                onValueChange = { viewModel.updateManualModelSwVer(it) },
                label = stringResource(R.string.label_model_sw_ver),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }
    }
}

@Composable
private fun SeriesDropdownCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    val seriesList = VivoDeviceDatabase.series
    val seriesIndex = seriesList.indexOf(state.selectedSeries).takeIf { it >= 0 } ?: 0
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.label_series),
            items = seriesList,
            selectedIndex = seriesIndex,
            onSelectedIndexChange = { viewModel.selectSeries(seriesList[it]) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ModelDropdownCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    val devices = VivoDeviceDatabase.devicesOf(state.selectedSeries)
    val deviceNames = devices.map { "${it.model} (${it.model_sw_ver})" }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.label_model),
            items = deviceNames,
            selectedIndex = state.selectedModelIndex,
            onSelectedIndexChange = { viewModel.selectDevice(it) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DeviceTypeCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    val phoneStr = stringResource(R.string.device_phone)
    val tabletStr = stringResource(R.string.device_tablet)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.label_device_type),
            items = listOf(phoneStr, tabletStr),
            selectedIndex = if (state.deviceType == "phone") 0 else 1,
            onSelectedIndexChange = { viewModel.updateDeviceType(if (it == 0) "phone" else "tablet") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AndroidVersionCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    val customStr = stringResource(R.string.custom_version)
    val androidVersions = listOf("13", "14", "15", "16", "17", customStr)
    val androidIndex = if (state.isCustomAndroidVersion) 5
        else androidVersions.indexOf(state.androidVersion.toString()).takeIf { it >= 0 } ?: 4
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.label_android_version),
                items = androidVersions,
                selectedIndex = androidIndex,
                onSelectedIndexChange = {
                    if (it == 4) {
                        viewModel.selectCustomAndroidVersion()
                    } else {
                        viewModel.updateAndroidVersion(androidVersions[it].toInt())
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        AnimatedVisibility(
            visible = state.isCustomAndroidVersion,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextField(
                    insideMargin = DpSize(16.dp, 24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    value = state.customAndroidVersion,
                    onValueChange = { viewModel.updateCustomAndroidVersion(it) },
                    label = stringResource(R.string.custom_version),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )
            }
        }
    }
}

@Composable
private fun CodenameInfoCard(state: VivoOtaUiState) {
    val codename = if (state.manualMode) state.manualCodename else state.selectedCodename
    val model = if (state.manualMode) state.manualModelName.ifEmpty { codename } else state.selectedModel
    val swVer = if (state.manualMode) state.manualModelSwVer else state.selectedModelSwVer
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.display_model, model))
            Text(stringResource(R.string.display_codename, codename), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (swVer.isNotEmpty()) {
                Text(stringResource(R.string.display_sw_ver, swVer), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

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
    // 可选版本号数组下拉：仅当机型配置了 optional_sw_versions 才出现
    val optional = if (state.manualMode) emptyList() else {
        VivoDeviceDatabase.devicesOf(state.selectedSeries)
            .getOrNull(state.selectedModelIndex)?.optionalSwVersions ?: emptyList()
    }
    // 当前版本号若在数组中则高亮对应项，否则默认 0（仅用于下拉初始定位）
    val optionalIndex = optional.indexOf(state.softwareVersion).takeIf { it >= 0 } ?: 0
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
            if (optional.isNotEmpty()) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.sw_version_optional),
                    items = optional,
                    selectedIndex = optionalIndex,
                    onSelectedIndexChange = { viewModel.applyOptionalSwVersion(optional[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
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

@Composable
private fun SnInputCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        TextField(
            insideMargin = DpSize(16.dp, 24.dp),
            modifier = Modifier.fillMaxWidth(),
            value = state.sn,
            onValueChange = { viewModel.updateSn(it) },
            label = stringResource(R.string.label_sn),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.query() })
        )
    }
}

// IMEI 只用于手机机型（pad 分支该字段传空串），平板下无需展示。
@Composable
private fun ImeiInputCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            TextField(
                insideMargin = DpSize(16.dp, 24.dp),
                modifier = Modifier.fillMaxWidth(),
                value = state.imei,
                onValueChange = { viewModel.updateImei(it) },
                label = stringResource(R.string.label_imei),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 手动填写时没有来源提示可说，只留一个占位把按钮推到右侧
                val hint = when (state.imeiSource) {
                    ImeiSource.DEVICE -> stringResource(R.string.imei_source_device)
                    ImeiSource.RANDOM -> stringResource(R.string.imei_source_random)
                    ImeiSource.MANUAL -> ""
                }
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.imei_use_device),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { viewModel.loadDeviceImei() }
                        .padding(start = 8.dp)
                )
                Text(
                    text = stringResource(R.string.imei_random),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { viewModel.generateRandomImei() }
                        .padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PackageTypeCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    val fullPkg = stringResource(R.string.pkg_full)
    val incrementalPkg = stringResource(R.string.pkg_incremental)
    // 尝鲜 / 公测 / 内测通道仅允许增量包
    val packageLocked = state.queryChannel != "NORMAL"
    val lockHint = stringResource(R.string.pkg_locked_incremental)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.label_package_type),
            items = listOf(fullPkg, incrementalPkg),
            selectedIndex = if (state.isFullPackage) 0 else 1,
            onSelectedIndexChange = { if (!packageLocked) viewModel.togglePackageType() },
            enabled = !packageLocked,
            modifier = Modifier.fillMaxWidth()
        )
        if (packageLocked) {
            Text(
                text = lockHint,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun ChannelCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    val channels = listOf(
        stringResource(R.string.channel_normal),
        stringResource(R.string.channel_trial),
        stringResource(R.string.channel_beta),
        stringResource(R.string.channel_alpha)
    )
    val channelValues = listOf("NORMAL", "TRIAL", "BETA", "ALPHA")
    val selectedIndex = channelValues.indexOf(state.queryChannel).takeIf { it >= 0 } ?: 0
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.label_query_channel),
            items = channels,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { viewModel.updateQueryChannel(channelValues[it]) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// 出口版域名已失效，暂时隐藏该选项。恢复时取消注释并在主 LazyColumn 中重新调用。
// @Composable
// private fun DomainCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
//     val domains = listOf(
//         stringResource(R.string.domain_cn),
//         stringResource(R.string.domain_global)
//     )
//     val domainValues = listOf("CN", "GLOBAL")
//     val selectedIndex = domainValues.indexOf(state.queryDomain).takeIf { it >= 0 } ?: 0
//     Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
//         OverlayDropdownPreference(
//             title = stringResource(R.string.label_query_domain),
//             items = domains,
//             selectedIndex = selectedIndex,
//             onSelectedIndexChange = { viewModel.updateQueryDomain(domainValues[it]) },
//             modifier = Modifier.fillMaxWidth()
//         )
//     }
// }

// 出口版域名已失效，暂时隐藏该选项。恢复时取消注释并在主 LazyColumn 中重新调用。
// @Composable
// private fun DomainCard(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
//     val domains = listOf(
//         stringResource(R.string.domain_cn),
//         stringResource(R.string.domain_global)
//     )
//     val domainValues = listOf("CN", "GLOBAL")
//     val selectedIndex = domainValues.indexOf(state.queryDomain).takeIf { it >= 0 } ?: 0
//     Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
//         OverlayDropdownPreference(
//             title = stringResource(R.string.label_query_domain),
//             items = domains,
//             selectedIndex = selectedIndex,
//             onSelectedIndexChange = { viewModel.updateQueryDomain(domainValues[it]) },
//             modifier = Modifier.fillMaxWidth()
//         )
//     }
// }

@Composable
private fun QueryButton(state: VivoOtaUiState, viewModel: VivoOtaViewModel) {
    val codename = if (state.manualMode) state.manualCodename else state.selectedCodename
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.query() },
            enabled = state.cryptoReady && !state.isLoading && codename.isNotEmpty() && state.softwareVersion.isNotEmpty(),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            if (state.isLoading) {
                Text(stringResource(R.string.querying))
            } else {
                Text(stringResource(R.string.btn_query))
            }
        }
    }
}

@Composable
private fun ErrorCard(error: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            text = stringResource(R.string.query_failed, error),
            color = Color(0xFFE53935),
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun ResultCard(
    result: VivoOtaResult,
    changelogContent: String?,
    currentVersion: String,
    androidVersion: Int,
    isFullPackage: Boolean
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val copiedMsg = stringResource(R.string.copied)
    val firmwareTypeStr = stringResource(if (isFullPackage) R.string.pkg_full else R.string.pkg_incremental)
    val channelStr = stringResource(
        when (result.channel) {
            "TRIAL" -> R.string.channel_trial
            "BETA" -> R.string.channel_beta
            "ALPHA" -> R.string.channel_alpha
            else -> R.string.channel_normal
        }
    )

    fun copyToClipboard(label: String, text: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.result_title), fontSize = 16.sp)
            if (currentVersion.isNotEmpty()) {
                Text(
                    stringResource(R.string.current_version, currentVersion),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard("version", currentVersion) }
                    )
                )
            }
            if (result.updateVersion.isNotEmpty() && result.updateVersion != "(Not found)") {
                Text(
                    stringResource(R.string.latest_version, result.updateVersion),
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard("version", result.updateVersion) }
                    )
                )
            }
            Text(stringResource(R.string.label_android_version) + ": $androidVersion", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(stringResource(R.string.label_package_type) + ": $firmwareTypeStr", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(stringResource(R.string.label_query_channel) + ": $channelStr", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (result.securityPatch.isNotEmpty() && result.securityPatch != "(Not found)") {
                Text(
                    stringResource(R.string.security_patch, result.securityPatch),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard("security", result.securityPatch) }
                    )
                )
            }
            if (result.updateDate.isNotEmpty() && result.updateDate != "(Not found)") {
                Text(
                    stringResource(R.string.update_date, result.updateDate),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard("date", result.updateDate) }
                    )
                )
            }
            if (result.filename.isNotEmpty() && result.filename != "(Not found)") {
                Text(
                    stringResource(R.string.filename, result.filename),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard("filename", result.filename) }
                    )
                )
            }
            if (result.fileSizeMb.isNotEmpty()) {
                Text(stringResource(R.string.size_mb, result.fileSizeMb), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (result.downloadUrl.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = result.downloadUrl,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl)))
                        },
                        onLongClick = { copyToClipboard("url", result.downloadUrl) }
                    )
                )
            }
            if (changelogContent != null) {
                HorizontalDivider()
                Text(stringResource(R.string.changelog_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (result.changelogUrl.isNotEmpty() && result.changelogUrl != "(Not found)") {
                    ChangelogLinkActions(url = result.changelogUrl, copiedMsg = copiedMsg)
                }
                if (changelogContent == "loading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.loading), modifier = Modifier.padding(start = 12.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                } else {
                    Text(
                        text = changelogContent,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { copyToClipboard("changelog", changelogContent) }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    state: VivoOtaUiState,
    viewModel: VivoOtaViewModel,
    payloadViewModel: VivoPayloadViewModel,
    onViewPartitions: (String) -> Unit,
    onViewChangelog: (QueryHistoryEntry) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val copiedMsg = stringResource(R.string.copied)
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val history = state.history
    val selectionMode = state.historySelectionMode
    val selected = state.selectedHistory

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode) {
                        Checkbox(
                            state = if (viewModel.isAllHistorySelected()) ToggleableState.On else ToggleableState.Off,
                            onClick = { viewModel.selectAllHistory() }
                        )
                        Text(
                            text = "${selected.size}/${history.size}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else {
                        Text(stringResource(R.string.history_title, history.size), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (expanded) " ▾" else " ▸",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
                if (selectionMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (selected.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.btn_delete_selected, selected.size),
                                color = Color(0xFFE53935),
                                modifier = Modifier.clickable { showDeleteConfirm = true }
                            )
                        }
                        Text(
                            text = stringResource(R.string.btn_done),
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.toggleHistorySelectionMode() }
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.btn_manage),
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewModel.toggleHistorySelectionMode() }
                    )
                }
            }
            AnimatedVisibility(
                visible = if (selectionMode) true else expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider()
                    history.take(10).forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider()
                        if (selectionMode) {
                            HistoryEntrySelectionRow(entry, selected.contains(entry.timestamp), viewModel)
                        } else {
                            SwipeToDeleteHistoryEntry(
                                entry = entry,
                                viewModel = viewModel,
                                context = context,
                                haptic = haptic,
                                copiedMsg = copiedMsg,
                                payloadViewModel = payloadViewModel,
                                onViewPartitions = onViewPartitions,
                                onViewChangelog = onViewChangelog
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            message = stringResource(R.string.confirm_delete_multiple, selected.size),
            onConfirm = { viewModel.deleteSelectedHistory() },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun HistoryEntrySelectionRow(
    entry: QueryHistoryEntry,
    isSelected: Boolean,
    viewModel: VivoOtaViewModel
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.toggleHistorySelection(entry.timestamp) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            state = if (isSelected) ToggleableState.On else ToggleableState.Off,
            onClick = { viewModel.toggleHistorySelection(entry.timestamp) }
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text("${entry.model} · ${viewModel.formatTime(entry.timestamp)}")
            Text(
                stringResource(R.string.history_query, entry.swVersion, entry.resultVersion),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp
            )
            if (entry.channel != "NORMAL") {
                val ch = stringResource(
                    when (entry.channel) {
                        "TRIAL" -> R.string.channel_trial
                        "BETA" -> R.string.channel_beta
                        "ALPHA" -> R.string.channel_alpha
                        else -> R.string.channel_normal
                    }
                )
                Text(ch, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp)
            }
            if (entry.fileSize.isNotEmpty()) {
                Text(
                    stringResource(R.string.history_size, entry.fileSize),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun SwipeToDeleteHistoryEntry(
    entry: QueryHistoryEntry,
    viewModel: VivoOtaViewModel,
    context: Context,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    copiedMsg: String,
    payloadViewModel: VivoPayloadViewModel,
    onViewPartitions: (String) -> Unit,
    onViewChangelog: (QueryHistoryEntry) -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(entry.timestamp) { Animatable(0f) }
    val threshold = 300f
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color(0xFFFF3B30)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\uD83D\uDDD1\uFE0F",
                color = Color.White,
                fontSize = 24.sp
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(entry.timestamp) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -threshold * 0.4f) {
                                    offsetX.animateTo(0f)
                                    showDeleteConfirm = true
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        }
                    ) { _, dragAmount ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-threshold * 2f, 0f))
                        }
                    }
                }
                .padding(16.dp)
        ) {
            Text("${entry.model} · ${viewModel.formatTime(entry.timestamp)}")
            Text(
                stringResource(R.string.history_query, entry.swVersion, entry.resultVersion),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp
            )
            if (entry.channel != "NORMAL") {
                val ch = stringResource(
                    when (entry.channel) {
                        "TRIAL" -> R.string.channel_trial
                        "BETA" -> R.string.channel_beta
                        "ALPHA" -> R.string.channel_alpha
                        else -> R.string.channel_normal
                    }
                )
                Text(ch, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp)
            }
            if (entry.fileSize.isNotEmpty()) {
                Text(
                    stringResource(R.string.history_size, entry.fileSize),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )
            }
            if (entry.downloadUrl.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.btn_copy_link),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("url", entry.downloadUrl))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("url", entry.downloadUrl))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MiuixTheme.colorScheme.primary)
                            .clickable { onViewPartitions(entry.downloadUrl) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.payload_dumper),
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MiuixTheme.colorScheme.primaryContainer)
                            .clickable { viewModel.fillHistoryBack(entry) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_fill),
                            color = MiuixTheme.colorScheme.onPrimaryContainer,
                            fontSize = 13.sp
                        )
                    }
                    if (entry.changelogUrl.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MiuixTheme.colorScheme.primaryContainer)
                                .clickable { onViewChangelog(entry) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.btn_changelog),
                                color = MiuixTheme.colorScheme.onPrimaryContainer,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            message = stringResource(R.string.confirm_delete),
            onConfirm = { viewModel.deleteHistoryEntry(entry.timestamp) },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(message, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.btn_cancel)) }
                        Button(
                            onClick = { onConfirm(); onDismiss() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(stringResource(R.string.btn_delete_selected, 0).substringBefore("(").trim())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val copiedMsg = stringResource(R.string.copied)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(stringResource(R.string.app_name), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    val appVersion = "v" + io.github.long36708.updater.AndroidAppContext.versionName
                    val appVersionCode = io.github.long36708.updater.AndroidAppContext.versionCode
                    Text(
                        text = "$appVersion ($appVersionCode)",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(stringResource(R.string.about_developer), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("mytiantian001", fontSize = 13.sp)
                    Text(
                        text = "Coolapk @mytiantian_是天天吖",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/4430874"))) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("url", "https://www.coolapk.com/u/4430874"))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Text("ℳℓ矜ℳℓ持", fontSize = 13.sp)
                    Text(
                        text = "Coolapk @ℳℓ矜ℳℓ持",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/922815"))) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("url", "https://www.coolapk.com/u/922815"))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(stringResource(R.string.about_reference), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "YuKongA / Updater-KMP",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/YuKongA/Updater-KMP"))) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("url", "https://github.com/YuKongA/Updater-KMP"))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "JerryTse-OSS / VIVO-OTA-Tracker",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/JerryTse-OSS/VIVO-OTA-Tracker"))) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("url", "https://github.com/JerryTse-OSS/VIVO-OTA-Tracker"))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(stringResource(R.string.about_source), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "VIVO-OTA-Tracker-Android",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mytiantian001/VIVO-OTA-Tracker-Android"))) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("url", "https://github.com/mytiantian001/VIVO-OTA-Tracker-Android"))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "© 2026 mytiantian001 · longmo",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        stringResource(R.string.about_disclaimer),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_close))
                    }
                }
            }
        }
    }
}
