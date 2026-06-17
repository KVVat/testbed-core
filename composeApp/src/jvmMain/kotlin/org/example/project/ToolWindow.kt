package org.example.project

import java.io.File
import org.example.project.tools.LogcatFilter
import kotlinx.coroutines.launch

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
fun ToolWindow(viewModel: ToolViewModel, onCloseRequest: () -> Unit) {
    val windowState = rememberWindowState(width = 1000.dp, height = 700.dp)
    val logcatLines = viewModel.logcatLines
    val logcatFilter by viewModel.logcatFilter.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val uiDumpRoot by viewModel.uiDumpRoot.collectAsState()
    val uiDumpScreenshot by viewModel.uiDumpScreenshot.collectAsState()
    val uiDumpScreenWidth by viewModel.uiDumpScreenWidth.collectAsState()
    val uiDumpScreenHeight by viewModel.uiDumpScreenHeight.collectAsState()
    val timelineItems by viewModel.timelineItems.collectAsState()
    val selectedTimelineIndex by viewModel.selectedTimelineIndex.collectAsState()
    val logcatBufferSize = viewModel.logcatBufferSize
    val isRootMode by viewModel.isRootMode.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var isWindowDialogOpen by remember { mutableStateOf(false) }

    // UI状態
    var isCompactMode by remember { mutableStateOf(false) } // Compact vs Standard
    var isPaused by remember { mutableStateOf(false) }
    var expandedLevelMenu by remember { mutableStateOf(false) }
    val selectedLevels = remember { mutableStateListOf(*LogLevel.values()) }
    var isSoftWrap by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    // フィルタリング
    val parsedFilter = remember(logcatFilter) {
        LogcatFilter.parse(logcatFilter)
    }
    val filteredLogs = remember(logcatLines.size, parsedFilter, selectedLevels.size) {
        logcatLines.filter { logcatLine ->
            LogcatFilter.matches(logcatLine, parsedFilter, selectedLevels)
        }
    }

    // 自動スクロール
    LaunchedEffect(filteredLogs.size) {
        if (!isPaused && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    Window(
        onCloseRequest = onCloseRequest, 
        state = windowState, 
        title = "Tool Box Window",
        undecorated = true,
        transparent = !isWindows
    ) {
        val awtWindow = this.window
        LaunchedEffect(awtWindow) {
            val dropTarget = java.awt.dnd.DropTarget(null, object : java.awt.dnd.DropTargetAdapter() {
                override fun dragEnter(dtde: java.awt.dnd.DropTargetDragEvent) {
                    if (viewModel.selectedTab.value == 2) {
                        dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
                    } else {
                        dtde.rejectDrag()
                    }
                }

                override fun dragOver(dtde: java.awt.dnd.DropTargetDragEvent) {
                    if (viewModel.selectedTab.value == 2) {
                        dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
                    } else {
                        dtde.rejectDrag()
                    }
                }

                override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                    if (viewModel.selectedTab.value == 2) {
                        try {
                            dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                            val transferable = dtde.transferable
                            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                                @Suppress("UNCHECKED_CAST")
                                val files = transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<File>
                                if (files != null) {
                                    files.forEach { file ->
                                        println("[SYSTEM] INFO: Dropped file to push: ${file.absolutePath}")
                                    }
                                    viewModel.pushDroppedFiles(files)
                                }
                            }
                        } catch (e: Exception) {
                            System.err.println("[SYSTEM] ERROR: Failed to process dropped files: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            })

            fun attachDropTarget(component: java.awt.Component) {
                component.dropTarget = dropTarget
                if (component is java.awt.Container) {
                    component.components.forEach { attachDropTarget(it) }
                }
            }

            attachDropTarget(awtWindow)
            if (awtWindow is javax.swing.JFrame) {
                awtWindow.contentPane?.let { attachDropTarget(it) }
            }
        }

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
                                "Tool Box Window",
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
                    } else if (selectedTab == 1) {
                        // UI Inspector Tools (Auto-polls on display, no manual buttons needed)
                    } else {
                        // File Explorer Tools
                        TooltipIconButton(
                            icon = Icons.Default.UploadFile,
                            tooltip = "Push File to Device",
                            tint = Color(0xFF569CD6),
                            onClick = {
                                if (isWindowDialogOpen) return@TooltipIconButton
                                isWindowDialogOpen = true
                                coroutineScope.launch {
                                    val hostPath = showOpenFileDialogSafe("Select File to Push")
                                    if (hostPath != null) {
                                        val fileName = File(hostPath).name
                                        val current = viewModel.currentPath.value
                                        val destPath = if (current.endsWith("/")) "$current$fileName" else "$current/$fileName"
                                        viewModel.pushFile(hostPath, destPath)
                                    }
                                    isWindowDialogOpen = false
                                }
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        TooltipIconButton(
                            icon = if (isRootMode) Icons.Default.LockOpen else Icons.Default.Lock,
                            tooltip = if (isRootMode) "Exit Root Mode" else "Enter Root Mode",
                            tint = if (isRootMode) Color(0xFFFF6B68) else Color.Gray,
                            onClick = { viewModel.toggleRootMode() }
                        )
                        Spacer(Modifier.height(16.dp))
                        TooltipIconButton(
                            icon = Icons.Default.Refresh,
                            tooltip = "Refresh Directory",
                            tint = Color.White,
                            onClick = { viewModel.refreshFileList() }
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
                    val snackbarHostState = remember { SnackbarHostState() }
                    
                    LaunchedEffect(Unit) {
                        viewModel.snackbarMessage.collect { msg ->
                            snackbarHostState.showSnackbar(msg)
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
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
                                    onClick = { viewModel.setTab(0) },
                                    text = { Text("Logcat") }
                                )
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { viewModel.setTab(1) },
                                    text = { Text("UI Inspector") }
                                )
                                Tab(
                                    selected = selectedTab == 2,
                                    onClick = { viewModel.setTab(2) },
                                    text = { Text("File Explorer") }
                                )
                            }

                    if (selectedTab == 0) {
                        // 上部: フィルタリングバー
                        LogcatTopBar(
                        filterText = logcatFilter,
                        onFilterChange = { text -> viewModel.updateLogcatFilter(text) },
                        selectedLevels = selectedLevels,
                        onLevelMenuOpen = { expandedLevelMenu = true },
                        levelMenuExpanded = expandedLevelMenu,
                        onLevelMenuDismiss = { expandedLevelMenu = false },
                        onToggleLevel = { level: LogLevel ->
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
                                    items(filteredLogs, key = { it.id }) { log ->
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
                            maxCount = logcatBufferSize,
                            filteredCount = filteredLogs.size
                        )
                    } else if (selectedTab == 1) {
                        // UI Inspector ツール
                        UiInspectorPane(viewModel = viewModel)
                    } else {
                        // File Explorer Pane
                        FileExplorerPane(viewModel)
                    } // end else
                } // end Column

                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                        )
                    } // end Box
                } // end right column
            } // end Row
            } // end Column
            } // end Surface
        } // end MaterialTheme
    } // end Window
}

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
        OutlinedTextField(
            value = filterText,
            onValueChange = onFilterChange,
            placeholder = { Text("Filter... use (process) to filter by process", color = Color.Gray, fontSize = 12.sp) },
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
        LogLevel.DEBUG -> Color(0xFF299999)
        LogLevel.INFO -> Color(0xFFBBBBBB)
        LogLevel.WARN -> Color(0xFFFFC66D)
        LogLevel.ERROR -> Color(0xFFFF6B68)
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
            .padding(vertical = 1.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .fillMaxHeight()
                .background(levelColor)
        )

        Spacer(Modifier.width(4.dp))

        val timeOnly = if (log.timestamp.length > 14) log.timestamp.substring(6,14) else log.timestamp

        val annotatedString = buildAnnotatedString {
            withStyle(style = SpanStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)) {
                append("${timeOnly} ")
            }
            withStyle(style = SpanStyle(color = levelColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)) {
                append(log.message.replace("\n", " "))
            }
        }
        Text(
            text = annotatedString,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            overflow = TextOverflow.Ellipsis,
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
