package org.example.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip

@Composable
fun LogcatWindow(viewModel: AppViewModel, onCloseRequest: () -> Unit) {
    val windowState = rememberWindowState(width = 1000.dp, height = 700.dp)
    val appSettings by viewModel.appSettings.collectAsState()
    val logcatLines = viewModel.logcatLines
    val logcatFilter by viewModel.logcatFilter.collectAsState()
    val selectedTab by viewModel.selectedToolWindowTab.collectAsState()
    val uiDumpRoot by viewModel.uiDumpRoot.collectAsState()
    val uiDumpScreenshot by viewModel.uiDumpScreenshot.collectAsState()
    val uiDumpScreenWidth by viewModel.uiDumpScreenWidth.collectAsState()
    val uiDumpScreenHeight by viewModel.uiDumpScreenHeight.collectAsState()

    // UI状態
    var isCompactMode by remember { mutableStateOf(false) } // Compact vs Standard
    var isPaused by remember { mutableStateOf(false) }
    var expandedLevelMenu by remember { mutableStateOf(false) }
    val selectedLevels = remember { mutableStateListOf(*LogLevel.values()) }
    var isSoftWrap by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    // フィルタリング
    val filteredLogs = remember(logcatLines.size, logcatFilter, selectedLevels.size) {
        logcatLines.filter { logcatLine ->
            val textMatches = if (logcatFilter.isBlank()) true
            else logcatLine.message.contains(logcatFilter, ignoreCase = true) ||
                    logcatLine.tag.contains(logcatFilter, ignoreCase = true)
            val levelMatches = selectedLevels.contains(logcatLine.level)
            textMatches && levelMatches
        }
    }

    // 自動スクロール
    LaunchedEffect(filteredLogs.size) {
        if (!isPaused && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    Window(
        onCloseRequest = onCloseRequest, 
        state = windowState, 
        title = "Logcat Pro",
        undecorated = true,
        transparent = true
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF3C3F41), RoundedCornerShape(10.dp)),
                color = Color(0xFF1E1F22)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    WindowDraggableArea {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(Color(0xFF2B2D30)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Logcat Pro",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // macOS style traffic lights
                            Row(
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Close
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF5F56))
                                        .clickable {
                                            onCloseRequest()
                                        }
                                )
                                // Minimize
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFBD2E))
                                        .clickable {
                                            windowState.isMinimized = true
                                        }
                                )
                                // Maximize/Restore
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF27C93F))
                                        .clickable {
                                            if (windowState.placement == WindowPlacement.Maximized) {
                                                windowState.placement = WindowPlacement.Floating
                                            } else {
                                                windowState.placement = WindowPlacement.Maximized
                                            }
                                        }
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {

                // --- 左側: コマンドパネル (Side Bar) ---
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF2B2D30))
                        .border(1.dp, Color(0xFF3C3F41)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(Modifier.height(16.dp))

                    Spacer(Modifier.height(16.dp))

                    if (selectedTab == 0) {
                        // 1. Play/Pause
                        TooltipIconButton(
                            icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            tooltip = if (isPaused) "Resume" else "Pause",
                            tint = if (isPaused) Color(0xFFFFC66D) else Color.White,
                            onClick = { isPaused = !isPaused }
                        )

                        Spacer(Modifier.height(16.dp))

                        // 2. Clear
                        TooltipIconButton(
                            icon = Icons.Default.DeleteSweep,
                            tooltip = "Clear Log",
                            onClick = { viewModel.clearLogcat() }
                        )
                        Spacer(Modifier.height(16.dp))
                        TooltipIconButton(
                            icon = Icons.AutoMirrored.Filled.Notes,
                            tooltip = if (isSoftWrap) "Disable Word Wrap" else "Enable Word Wrap",
                            tint = if (isSoftWrap) Color(0xFF569CD6) else Color.White,
                            onClick = { isSoftWrap = !isSoftWrap }
                        )
                        Spacer(Modifier.height(16.dp))

                        TooltipIconButton(
                            icon = if (isCompactMode) Icons.Default.ViewHeadline else Icons.AutoMirrored.Filled.ViewList,
                            tooltip = if (isCompactMode) "Standard View" else "Compact View",
                            tint = if (isCompactMode) Color(0xFF569CD6) else Color.White,
                            onClick = { isCompactMode = !isCompactMode }
                        )
                        Spacer(Modifier.height(16.dp))
                        TooltipIconButton(onClick = {
                            val textToCopy = filteredLogs.joinToString("\n") {log->
                                "${log.timestamp} ${log.pid}/${log.tag} ${log.level.name}: ${log.message}"
                            }
                            clipboardManager.setText(AnnotatedString(textToCopy))
                            } ,
                            tooltip =  "Copy Shown Logs",
                            tint = Color.Gray,
                            icon = Icons.Default.ContentCopy
                        )
                    } else {
                        // UI Inspector Tools
                        TooltipIconButton(
                            icon = Icons.Default.CellTower,
                            tooltip = "Ping Agent",
                            tint = Color(0xFF569CD6),
                            onClick = { viewModel.pingMuttonAgent() }
                        )
                        Spacer(Modifier.height(16.dp))
                        TooltipIconButton(
                            icon = Icons.Default.AccountTree,
                            tooltip = "Dump UI Tree",
                            tint = Color(0xFFFFC66D),
                            onClick = { viewModel.dumpMuttonAgent() }
                        )
                    }

                    Spacer(Modifier.weight(1f)) // 下詰め

                    // 4. Settings (Placeholder)
                    TooltipIconButton(
                        icon = Icons.Default.Settings,
                        tooltip = "Settings",
                        onClick = { /* Open Settings */ }
                    )


                    Spacer(Modifier.height(16.dp))
                }

                // --- 右側: メインコンテンツ ---
                Column(modifier = Modifier.weight(1f)) {
                    
                    // タブエリア
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF2B2D30),
                        contentColor = Color.White,
                        indicator = { tabPositions ->
                            TabRowDefaults.Indicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = Color(0xFF569CD6)
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { viewModel.setToolWindowTab(0) },
                            text = { Text("Logcat") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { viewModel.setToolWindowTab(1) },
                            text = { Text("UI Inspector") }
                        )
                    }

                    if (selectedTab == 0) {
                        // 上部: フィルタリングバー
                        LogcatTopBar(
                        filterText = logcatFilter,
                        onFilterChange = { viewModel.updateLogcatFilter(it) },
                        selectedLevels = selectedLevels,
                        onLevelMenuOpen = { expandedLevelMenu = true },
                        levelMenuExpanded = expandedLevelMenu,
                        onLevelMenuDismiss = { expandedLevelMenu = false },
                        onToggleLevel = { level ->
                            if (selectedLevels.contains(level)) {
                                if (selectedLevels.size > 1) selectedLevels.remove(level)
                            } else {
                                selectedLevels.add(level)
                            }
                        }
                    )

                    // ログリスト
                    val selectionColor = TextSelectionColors(
                        handleColor = Color(0xFF569CD6),
                        backgroundColor = Color(0xFF264F78)
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalTextSelectionColors provides selectionColor) {
                            SelectionContainer {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(filteredLogs) { log ->
                                        if (isCompactMode) {
                                            CompactLogItem(log,isSoftWrap)
                                        } else {
                                            StandardLogItem(log, isSoftWrap)
                                        }
                                        Divider(color = Color(0xFF2B2D30), thickness = 0.5.dp)
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(listState)
                        )
                    }

                        // フッター (ステータスバー)
                        LogcatFooter(
                            currentCount = logcatLines.size,
                            maxCount = appSettings.logcatBufferSize,
                            filteredCount = filteredLogs.size
                        )
                    } else {
                        // UI Inspector ツール
                        UiInspectorPane(
                            rootNode = uiDumpRoot,
                            screenshot = uiDumpScreenshot,
                            screenWidth = uiDumpScreenWidth,
                            screenHeight = uiDumpScreenHeight
                        )
                    }
                } // end right column
            } // end Row
            } // end Column
            } // end Surface
        } // end MaterialTheme
    } // end Window
} // end fun

// --- コンポーネント定義 ---

@Composable
fun LogcatTopBar(
    filterText: String,
    onFilterChange: (String) -> Unit,
    selectedLevels: List<LogLevel>,
    onLevelMenuOpen: () -> Unit,
    levelMenuExpanded: Boolean,
    onLevelMenuDismiss: () -> Unit,
    onToggleLevel: (LogLevel) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3C3F41))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        /*
        onValueChange = { filterText = it },
        placeholder = { Text("Filter (grep)...", color = Color.Gray) },
        modifier = Modifier.weight(1f).height(50.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color.White),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF569CD6),
            unfocusedBorderColor = Color.Gray,
            cursorColor = Color.White,
            // 文字色もここで指定するのが Material 3 の推奨です
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),*/
        // Filter TextField
        OutlinedTextField(
            value = filterText,
            onValueChange = onFilterChange,
            placeholder = { Text("Filter (tag, msg)...", color = Color.Gray, fontSize = 12.sp) },
            modifier = Modifier.weight(1f).height(60.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color.White),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF569CD6),
                unfocusedBorderColor = Color.Gray,
                cursorColor = Color.White,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            ),
            trailingIcon = {
                if (filterText.isNotEmpty()) {
                    IconButton(onClick = { onFilterChange("") }) {
                        Icon(Icons.Default.Close, "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }
        )

        Spacer(Modifier.width(8.dp))

        // Level Dropdown
        Box {
            Button(
                onClick = onLevelMenuOpen,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C5052)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    if (selectedLevels.size == LogLevel.values().size) "All Levels" else "${selectedLevels.size} Levels",
                    fontSize = 12.sp,
                    color = Color.White
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
            }

            DropdownMenu(
                expanded = levelMenuExpanded,
                onDismissRequest = onLevelMenuDismiss,
                modifier = Modifier.background(Color(0xFF2B2D30)).border(1.dp, Color.Gray)
            ) {
                LogLevel.values().forEach { level ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selectedLevels.contains(level),
                                    onCheckedChange = null, // Handled by onClick
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF569CD6))
                                )
                                Text(level.name, color = Color.White, fontSize = 13.sp)
                            }
                        },
                        onClick = { onToggleLevel(level) }
                    )
                }
            }
        }
    }
}


@Composable
fun StandardLogItem(log :LogLine,isSoftWrap:Boolean){
    val levelColor = when (log.level) {
        LogLevel.DEBUG -> Color(0xFF299999) // Cyan-ish
        LogLevel.INFO -> Color(0xFFBBBBBB)  // Light Gray
        LogLevel.WARN -> Color(0xFFFFC66D)  // Orange
        LogLevel.ERROR -> Color(0xFFFF6B68) // Red
        else -> Color.Gray
    }
   Row(modifier = Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Surface(color = levelColor, shape = RoundedCornerShape(4.dp), modifier = Modifier.size(20.dp).padding(top = 1.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = log.level.name.first().toString(),
                    modifier = Modifier.padding(start = 0.dp),
                    color = Color(0xFF1E1F22),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = buildAnnotatedString {
                append("${log.timestamp}  ")
                append(log.pid)
                // ★パッケージ名が判明していれば表示
                log.packageName?.let { pkg ->
                    append(" ($pkg)")
                }
                append(" ${log.tag}")
                append(" ${log.message}")
            },
            color = levelColor, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            lineHeight = 18.sp, softWrap = isSoftWrap)
    }
}
@Composable
fun StandardLogItem_(log: LogLine) {
    val levelColor = when (log.level) {
        LogLevel.DEBUG -> Color(0xFF299999) // Cyan-ish
        LogLevel.INFO -> Color(0xFFBBBBBB)  // Light Gray
        LogLevel.WARN -> Color(0xFFFFC66D)  // Orange
        LogLevel.ERROR -> Color(0xFFFF6B68) // Red
        else -> Color.Gray
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        // 左端: レベルを表すカラーバー (アイコン代わりの視認性確保)
        Box(
            modifier = Modifier
                .width(8.dp)
                .fillMaxHeight()
                .background(levelColor)
        )


        // 1行目: ヘッダー情報 (暗めの色で)
        // 例: 02-13 17:02:12.123 D/Tag
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color.Gray)) {
                    append("${log.timestamp}  ")
                }
                withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) {
                    append("${log.level.name.first()}/${log.tag}")
                }
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )

        // 2行目以降: メッセージ本文 (白でくっきり、改行あり)
        Text(
            text = log.message,
            color = if (log.level == LogLevel.ERROR) Color(0xFFFF6B68) else Color(0xFFE0E0E0),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp) // インデントをつけて見やすく
        )
    }
}
@Composable
fun CompactLogItem(log: LogLine,isSoftWrap:Boolean) {
    val levelColor = when (log.level) {
        LogLevel.DEBUG -> Color(0xFF299999)
        LogLevel.INFO -> Color(0xFFBBBBBB)
        LogLevel.WARN -> Color(0xFFFFC66D)
        LogLevel.ERROR -> Color(0xFFFF6B68)
        else -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp) // 上下の余白を詰める
            .height(IntrinsicSize.Min) // 高さを揃える
    ) {
        // 左端: レベルを表すカラーバー (アイコン代わりの視認性確保)
        Box(
            modifier = Modifier
                .width(8.dp)
                .fillMaxHeight()
                .background(levelColor)
        )

        Spacer(Modifier.width(4.dp))

        // 時刻のみ: 幅を広げて折り返し防止
        val timeOnly = if (log.timestamp.length > 14) log.timestamp.substring(6,14) else log.timestamp

        val annotatedString = buildAnnotatedString {
            // 1. メタデータ部分 (時刻 PID TAGなど)
            withStyle(style = SpanStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)) {
                append("${timeOnly} ")
            }
            // 2. 本文メッセージ
            withStyle(style = SpanStyle(color = levelColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)) {
                append(log.message.replace("\n", " "))
            }
        }
        // メッセージ
        Text(
            text = annotatedString,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            //maxLines = 1, // ★1行制限
            overflow = TextOverflow.Ellipsis, // ★あふれたら "..."
            softWrap = isSoftWrap,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
        )
    }
}

@Composable
fun LogcatFooter(currentCount: Int, maxCount: Int, filteredCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2B2D30))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Buffer: $currentCount / $maxCount",
            fontSize = 11.sp,
            color = if (currentCount >= maxCount) Color(0xFFFFC66D) else Color.Gray
        )
        Text(
            "Filtered: $filteredCount",
            fontSize = 11.sp,
            color = Color.White
        )
    }
}