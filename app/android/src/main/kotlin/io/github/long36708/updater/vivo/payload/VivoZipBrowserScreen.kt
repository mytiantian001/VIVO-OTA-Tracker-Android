package io.github.long36708.updater.vivo.payload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.long36708.updater.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 包内文件浏览页（ADR-002 D1）。
 *
 * 独立全屏页面，而非内联区块——它和「分区列表」是两件不同的事，
 * 共用滚动容器是层级混乱的根源。
 *
 * 返回键语义（这是选全屏页而非 Dialog 的关键原因）：
 * 优先退出文件夹，其次退出嵌套包，最后退出整个页面。
 * Dialog 的返回键会直接关掉整个弹窗，丢失全部导航状态。
 */
@Composable
fun ZipBrowserScreen(
    viewModel: VivoPayloadViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.zipState.collectAsState()
    val context = LocalContext.current

    BackHandler(enabled = true) {
        when {
            viewModel.canExitZipFolder() -> viewModel.exitZipFolder()
            viewModel.canNavigateZipUp() -> viewModel.navigateZipUp(context)
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                // ADR-002 D5：标题动态表达「我在哪」，过长时中间段省略
                title = buildTitle(state),
                navigationIcon = {
                    Text(
                        text = "←",
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable {
                                when {
                                    viewModel.canExitZipFolder() -> viewModel.exitZipFolder()
                                    viewModel.canNavigateZipUp() -> viewModel.navigateZipUp(context)
                                    else -> onBack()
                                }
                            },
                        fontSize = 20.sp
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TextField(
                insideMargin = DpSize(16.dp, 18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                value = state.searchQuery,
                onValueChange = { viewModel.updateZipSearch(it) },
                label = stringResource(R.string.zip_search_hint),
                singleLine = true
            )

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.zip_loading),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }

                state.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Text(text = "⚠", fontSize = 28.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.error!!,
                                fontSize = 13.sp,
                                color = Color(0xFFE53935)
                            )
                        }
                    }
                }

                else -> {
                    ZipEntryList(
                        state = state,
                        context = context,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

/**
 * 顶栏标题：`ota.zip / nested.zip / META-INF`，过长时中间段省略。
 * 全文放不下时需保留首尾，因为首段是包名、末段是当前位置。
 */
private fun buildTitle(state: ZipBrowserState): String {
    val parts = ArrayList<String>()
    parts.addAll(state.breadcrumb)
    state.currentFolder?.let { parts.add(it) }
    if (parts.isEmpty()) return "…"
    if (parts.size <= 3) return parts.joinToString(" / ")
    return "${parts.first()} / … / ${parts.last()}"
}

@Composable
private fun ZipEntryList(
    state: ZipBrowserState,
    context: Context,
    viewModel: VivoPayloadViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        if (state.isRootView) {
            // ADR-002 D2：顶层为「合成文件夹 + 根目录散装文件」
            items(state.rootItems) { item ->
                when (item) {
                    is ZipListItem.Folder -> FolderItem(
                        folder = item,
                        onClick = { viewModel.enterZipFolder(item.name) }
                    )
                    is ZipListItem.Entry -> FileRow(
                        entry = item.entry,
                        displayName = item.entry.name,
                        isDownloading = state.downloadingEntryName == item.entry.name,
                        onPreview = { viewModel.previewEntry(context, item.entry) },
                        onOpenZip = { viewModel.openNestedZip(context, item.entry) },
                        onDownload = { viewModel.downloadEntry(context, item.entry) }
                    )
                }
            }
        } else {
            if (state.viewEntries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.zip_no_match),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // ADR-002 D3：提示可进入嵌套包继续搜索，把选择权交回用户
                            Text(
                                text = stringResource(R.string.zip_no_match_hint),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            } else {
                items(state.viewEntries) { entry ->
                    FileRow(
                        entry = entry,
                        // 文件夹内/搜索结果用相对路径，避免同名文件无法区分
                        displayPath = relativeName(entry.name, state.currentFolder),
                        isDownloading = state.downloadingEntryName == entry.name,
                        onPreview = { viewModel.previewEntry(context, entry) },
                        onOpenZip = { viewModel.openNestedZip(context, entry) },
                        onDownload = { viewModel.downloadEntry(context, entry) }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/** 去掉已进入的文件夹前缀，得到相对路径。 */
private fun relativeName(name: String, folder: String?): String {
    return if (folder != null && name.startsWith("$folder/")) {
        name.removePrefix("$folder/")
    } else {
        name
    }
}

@Composable
private fun FolderItem(folder: ZipListItem.Folder, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "📁", fontSize = 18.sp)
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${folder.count} · ${formatBytes(folder.totalSize)}",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Text(
                text = "›",
                fontSize = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: ZipEntryInfo,
    displayName: String = entry.name.substringAfterLast('/'),
    displayPath: String? = null,
    isDownloading: Boolean,
    onPreview: () -> Unit,
    onOpenZip: () -> Unit,
    onDownload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (displayPath != null) {
                    // ADR-002 D6：路径段灰小字 + 文件名正常字重，单行不增行高
                    Text(text = mixedPathText(displayPath), fontSize = 13.sp)
                } else {
                    Text(
                        text = displayName.ifBlank { entry.name },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatBytes(entry.compressedSize)} → ${formatBytes(entry.uncompressedSize)}",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Spacer(modifier = Modifier.size(8.dp))
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else if (entry.isNestedZip) {
                // ADR-002 D8：嵌套 zip 不伪装成目录，本质是文件
                if (entry.canDownload) {
                    Button(onClick = onOpenZip) {
                        Text(stringResource(R.string.zip_open_zip), fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.zip_download), fontSize = 12.sp)
                }
            } else if (entry.canDownload) {
                if (entry.canPreview) {
                    Button(onClick = onPreview) {
                        Text(stringResource(R.string.zip_preview), fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.zip_download), fontSize = 12.sp)
                }
            } else if (!entry.isDirectory) {
                Text(
                    text = stringResource(
                        if (entry.isSupported) R.string.zip_too_large else R.string.zip_unsupported
                    ),
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

/** 末段（文件名）正常字重，前面的路径段灰小字。 */
@Composable
private fun mixedPathText(path: String): AnnotatedString {
    val lastSlash = path.lastIndexOf('/')
    return buildAnnotatedString {
        if (lastSlash >= 0) {
            withStyle(
                SpanStyle(
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            ) {
                append(path.substring(0, lastSlash + 1))
            }
            withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)) {
                append(path.substring(lastSlash + 1))
            }
        } else {
            withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)) {
                append(path)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * 文本预览页（ADR-002 D4）。
 *
 * 全屏而非 Dialog：`updater-script` 可达数十 KB，Dialog 限高 460dp 过于憋屈。
 * 操作栏固定在底部，滚动长文本时入口始终可见。
 * 「加载完整内容」是状态相关的一次性动作，故紧跟截断提示放在文本区下方，
 * 而非常驻底部。
 */
@Composable
fun ZipPreviewScreen(
    viewModel: VivoPayloadViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.zipState.collectAsState()
    val entry = state.previewEntry ?: return
    val context = LocalContext.current

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = entry.name.substringAfterLast('/').ifBlank { entry.name },
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
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val text = state.preview?.text
                            if (text != null && state.preview?.canCopyAll == true) {
                                copyPreviewText(context, text)
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.zip_too_large_to_copy),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.zip_copy_all))
                    }
                    if (entry.canDownload) {
                        Button(
                            onClick = { viewModel.downloadEntry(context, entry) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(stringResource(R.string.zip_download))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isPreviewLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.zip_loading),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }

                state.previewError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = state.previewError!!,
                            fontSize = 13.sp,
                            color = Color(0xFFE53935),
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }

                state.preview != null -> {
                    val preview = state.preview!!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.zip_encoding) + ": " + preview.encoding,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        if (preview.truncated) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.zip_truncated_hint,
                                    formatBytes(preview.bytesLoaded),
                                    formatBytes(preview.totalSize)
                                ),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            // 状态相关的一次性动作，紧跟截断提示
                            if (entry.canDownload) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.previewEntry(context, entry, loadFull = true) }
                                ) {
                                    Text(stringResource(R.string.zip_load_full), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // ADR-001 D8：Compose 的 Text 默认不可选中，必须包 SelectionContainer；
                    // 用原生 BasicText 而非 miuix 的 Text，确保拖选一定生效。
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        BasicText(
                            text = preview.text,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Binder 事务上限约 1MB，复制超大文本会抛 TransactionTooLargeException（ADR-001 D8）。
 * 兜住异常，避免预览大文本并点「复制」时崩溃。
 */
private fun copyPreviewText(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("preview", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.zip_too_large_to_copy), Toast.LENGTH_LONG).show()
    }
}
