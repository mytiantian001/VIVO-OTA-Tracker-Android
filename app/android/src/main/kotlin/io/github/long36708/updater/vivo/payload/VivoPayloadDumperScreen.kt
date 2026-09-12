package io.github.long36708.updater.vivo.payload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.long36708.updater.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.window.Dialog

@Composable
fun PayloadDumperScreen(
    viewModel: VivoPayloadViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val zipState by viewModel.zipState.collectAsState()
    val context = LocalContext.current
    // ADR-002 D1：页面状态机保持在 payload 模块内部，不侵入 VivoApp
    var showZipBrowser by remember { mutableStateOf(false) }
    var showZipPreview by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.parseRequestId) {
        if (uiState.parseRequestId > 0 && uiState.pathOrUrl.isNotBlank()) {
            viewModel.parsePayload(uiState.pathOrUrl)
        }
    }

    LaunchedEffect(Unit) {
            viewModel.toastEvent.collect { toast ->
                val msg = when (toast) {
                    is PayloadToast.ExtractSuccess -> context.getString(R.string.payload_extract_success, toast.partitionName)
                    is PayloadToast.ExtractFailed -> context.getString(R.string.payload_extract_failed, toast.partitionName)
                    is PayloadToast.BatchComplete -> context.getString(R.string.payload_batch_complete, toast.success, toast.fail)
                    is PayloadToast.Error -> toast.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
    }

    LaunchedEffect(Unit) {
        viewModel.zipMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // 预览内容就绪后进入预览页；解析失败时停留在浏览页内显示错误
    LaunchedEffect(zipState.previewEntry) {
        showZipPreview = zipState.previewEntry != null
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.payload_dumper),
                actions = {
                    if (uiState.filteredPartitions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    if (viewModel.isAllSelected()) viewModel.deselectAll() else viewModel.selectAll()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                state = if (viewModel.isAllSelected()) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    if (viewModel.isAllSelected()) viewModel.deselectAll() else viewModel.selectAll()
                                }
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(
                                text = stringResource(R.string.payload_select_all),
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    Text(
                        text = "←",
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable { onBack() },
                        fontSize = 20.sp
                    )
                }
            )
        },
        bottomBar = {
            if (uiState.selectedPartitions.isNotEmpty() && !uiState.isExtracting) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.payload_selected_count, uiState.selectedPartitions.size),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = { viewModel.extractSelectedPartitions(context) },
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(stringResource(R.string.payload_extract_selected))
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (uiState.isParsing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.payload_loading),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            insideMargin = DpSize(16.dp, 24.dp),
                            value = uiState.inputUrl,
                            onValueChange = { viewModel.updateInputUrl(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 160.dp),
                            label = stringResource(R.string.payload_input_hint),
                            singleLine = false,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.clearInputUrl() },
                                modifier = Modifier.weight(1f),
                                enabled = uiState.inputUrl.isNotBlank() && !uiState.isParsing
                            ) {
                                Text(stringResource(R.string.payload_clear))
                            }
                            Button(
                                onClick = { viewModel.submitUrl() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                enabled = !uiState.isParsing
                            ) {
                                Text(stringResource(R.string.payload_parse))
                            }
                        }
                    }
                }

                if (uiState.error != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⚠",
                                    fontSize = 20.sp
                                )
                                Spacer(modifier = Modifier.size(12.dp))
                                Text(
                                    text = uiState.error!!,
                                    fontSize = 13.sp,
                                    color = Color(0xFFE53935)
                                )
                            }
                        }
                    }
                }

                uiState.archiveInfo?.let { archiveInfo ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.rom_info),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                InfoRow(stringResource(R.string.file_name), archiveInfo.fileName)
                                InfoRow(stringResource(R.string.file_size), formatFileSize(archiveInfo.fileSize))
                                if (archiveInfo.securityPatchLevel.isNotEmpty()) {
                                    InfoRow(
                                        stringResource(R.string.payload_security_patch),
                                        archiveInfo.securityPatchLevel
                                    )
                                }
                                if (archiveInfo.buildDate.isNotEmpty()) {
                                    InfoRow(
                                        stringResource(R.string.payload_build_date),
                                        archiveInfo.buildDate
                                    )
                                }
                                InfoRow(
                                    stringResource(R.string.payload_package_type),
                                    if (archiveInfo.minorVersion == 0) {
                                        stringResource(R.string.payload_full)
                                    } else {
                                        stringResource(R.string.payload_incremental)
                                    }
                                )
                                if (archiveInfo.blockSize > 0) {
                                    InfoRow(
                                        stringResource(R.string.payload_block_size),
                                        "${archiveInfo.blockSize} B"
                                    )
                                }
                                // ADR-004 D9：本次解析真实抓取的字节数，证明「没有下载整包」
                                if (uiState.readTrafficBytes > 0) {
                                    InfoRow(
                                        stringResource(R.string.payload_read_traffic),
                                        formatFileSize(uiState.readTrafficBytes)
                                    )
                                }
                                if (archiveInfo.partialUpdate) {
                                    InfoRow(
                                        stringResource(R.string.payload_partial_update),
                                        stringResource(R.string.payload_yes)
                                    )
                                }
                            }
                        }
                    }
                }

                // ADR-002 D1：包内文件改为入口卡片，点击进入独立全屏页面。
                // 不再内联展开——它与分区列表是两件不同的事，共用滚动容器是层级混乱的根源。
                if (uiState.pathOrUrl.isNotBlank()) {
                    item {
                        ZipBrowserEntryCard(
                            fileCount = zipState.entries.size,
                            onClick = {
                                showZipBrowser = true
                                viewModel.loadZipEntries(context)
                            }
                        )
                    }
                }

                if (uiState.filteredPartitions.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.partition_list),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(uiState.filteredPartitions) { partitionInfo ->
                        PartitionItem(
                            partitionInfo = partitionInfo,
                            isSelected = uiState.selectedPartitions.contains(partitionInfo.partitionName),
                            isExtracting = uiState.isExtracting,
                            onToggleSelect = { viewModel.toggleSelection(partitionInfo.partitionName) },
                            onItemClick = { viewModel.selectPartition(partitionInfo.partitionName) },
                            onExtract = { viewModel.extractPartition(context, partitionInfo) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    uiState.selectedPartition?.let { partitionInfo ->
        PartitionDetailDialog(
            partitionInfo = partitionInfo,
            onDismiss = { viewModel.clearSelectedPartition() },
            onExtract = { viewModel.extractPartition(context, partitionInfo) }
        )
    }

    // ADR-002 D1/D4：全屏页面栈。浏览页在前、预览页覆盖其上。
    if (showZipBrowser) {
        ZipBrowserScreen(
            viewModel = viewModel,
            onBack = {
                showZipBrowser = false
                viewModel.dismissPreview()
            }
        )
    }
    if (showZipPreview) {
        ZipPreviewScreen(
            viewModel = viewModel,
            onBack = { viewModel.dismissPreview() }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PartitionItem(
    partitionInfo: PartitionInfo,
    isSelected: Boolean,
    isExtracting: Boolean,
    onToggleSelect: () -> Unit,
    onItemClick: () -> Unit,
    onExtract: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                onClick = onToggleSelect
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = partitionInfo.partitionName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.partition_size_ops,
                        formatFileSize(partitionInfo.size),
                        partitionInfo.operationsCount,
                        partitionInfo.mergeOperationsCount
                    ),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                if (partitionInfo.sha256.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.sha256, partitionInfo.sha256.take(16) + "..."),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("SHA256", partitionInfo.sha256)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            if (partitionInfo.isDownloading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatFileSize(partitionInfo.progress.toLong()) + "/" + formatFileSize(partitionInfo.size),
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else if (!isExtracting) {
                // 自绘紧凑文字按钮：圆角主色背景 + 白字，padding 完全可控，
                // 规避 miuix Button 内置 contentPadding 把「提取」两字裁切的问题
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable(onClick = onExtract)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.extract),
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun PartitionDetailDialog(
    partitionInfo: PartitionInfo,
    onDismiss: () -> Unit,
    onExtract: () -> Unit
) {
    val context = LocalContext.current
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
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = partitionInfo.partitionName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow(stringResource(R.string.partition_size), formatFileSize(partitionInfo.size))
                    InfoRow(stringResource(R.string.partition_raw_size), formatFileSize(partitionInfo.rawSize))
                    InfoRow(
                        stringResource(R.string.partition_ops),
                        stringResource(R.string.partition_ops_value, partitionInfo.operationsCount, partitionInfo.mergeOperationsCount)
                    )
                    if (partitionInfo.sha256.isNotEmpty()) {
                        InfoRow(stringResource(R.string.sha256_full), partitionInfo.sha256)
                    }
                    if (partitionInfo.typeStats.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.partition_op_types),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        partitionInfo.typeStats.toList()
                            .sortedByDescending { it.second }
                            .forEach { (type, count) ->
                                InfoRow(type, count.toString())
                            }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onExtract()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(stringResource(R.string.extract))
                    }
                }
            }
        }
    }
}


private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

// ===== 包内文件入口（ADR-002 D1：点击进入独立全屏页面）=====

/** 「包内文件」入口卡片，显示文件数概览。 */
@Composable
private fun ZipBrowserEntryCard(
    fileCount: Int,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.zip_package_files),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (fileCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.zip_file_count, fileCount),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            Text(
                text = "›",
                fontSize = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}
