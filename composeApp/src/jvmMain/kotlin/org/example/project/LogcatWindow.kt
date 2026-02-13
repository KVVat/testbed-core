package org.example.project

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

@Composable
fun LogcatWindow(viewModel: AppViewModel, onCloseRequest: () -> Unit) {
    val windowState = rememberWindowState(width = 900.dp, height = 600.dp)

    // データと状態
    val logcatLines = viewModel.logcatLines
    val logcatFilter by viewModel.logcatFilter.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()

    val selectedLevels = remember { mutableStateListOf(*LogLevel.values()) }
    var expanded by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) } // 一時停止状態
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    // フィルタリングロジック
    val filteredLogs = remember(logcatLines.size, logcatFilter, selectedLevels.size) {
        logcatLines.filter { logcatLine ->
            val textMatches = if (logcatFilter.isBlank()) true
            else logcatLine.message.contains(logcatFilter, ignoreCase = true) ||
                    logcatLine.tag.contains(logcatFilter, ignoreCase = true)

            val levelMatches = selectedLevels.contains(logcatLine.level)
            textMatches && levelMatches
        }
    }

    // 自動スクロール (停止中以外)
    LaunchedEffect(filteredLogs.size) {
        if (!isPaused && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "Logcat Monitor",
    ) {
        // MaterialTheme を適用
        MaterialTheme(colorScheme = darkColorScheme()) {
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {

                // --- ツールバー ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3C3F41))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // テキストフィルタ入力
                    OutlinedTextField(
                        value = logcatFilter,
                        onValueChange = { viewModel.updateLogcatFilter(it) },
                        placeholder = { Text("Filter (tag/msg)...", color = Color.Gray) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color.White),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF569CD6),
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        trailingIcon = {
                            if (logcatFilter.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateLogcatFilter("") }) {
                                    Icon(Icons.Default.Close, "Clear", tint = Color.Gray)
                                }
                            }
                        }
                    )

                    Spacer(Modifier.width(8.dp))

                    // --- ログレベル選択 DropDown ---
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.height(50.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.FilterList, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            if (selectedLevels.size == LogLevel.values().size) {
                                Text("All Levels", fontSize = 12.sp)
                            } else {
                                Text(
                                    selectedLevels.joinToString(",") { it.name.take(1) },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF569CD6)
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, null)
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(Color(0xFF2B2D30)).border(1.dp, Color.Gray)
                        ) {
                            LogLevel.values().forEach { level ->
                                val isSelected = selectedLevels.contains(level)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = null,
                                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF569CD6))
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(level.name, color = Color.White, fontSize = 13.sp)
                                        }
                                    },
                                    onClick = {
                                        if (isSelected) {
                                            if (selectedLevels.size > 1) selectedLevels.remove(level)
                                        } else {
                                            selectedLevels.add(level)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // --- 操作ボタン ---
                    // 一時停止
                    IconButton(onClick = { isPaused = !isPaused }) {
                        Icon(
                            if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Pause",
                            tint = if (isPaused) Color(0xFFFFC66D) else Color.Gray
                        )
                    }

                    // コピー (表示されているログをクリップボードへ)
                    IconButton(onClick = {
                        val textToCopy = filteredLogs.joinToString("\n") {
                            "${it.timestamp} ${it.level} [${it.tag}] ${it.message}"
                        }
                        clipboardManager.setText(AnnotatedString(textToCopy))
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy", tint = Color.Gray)
                    }

                    // クリア (バッファの消去)
                    IconButton(onClick = { viewModel.clearLogcat() }) {
                        Icon(Icons.Default.DeleteSweep, "Clear Buffer", tint = Color.Gray)
                    }
                }

                // --- ログリスト本体 ---
                val selectionColor = TextSelectionColors(
                    handleColor = Color(0xFF569CD6),
                    backgroundColor = Color(0xFF264F78)
                )

                Box(modifier = Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalTextSelectionColors provides selectionColor) {
                        SelectionContainer {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.padding(8.dp).fillMaxSize()
                            ) {
                                items(filteredLogs) { log ->
                                    // App.kt で定義済みの LogLineItem をそのまま再利用
                                    LogLineItem(log)
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(listState)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2B2D30)) // ツールバーより少し暗めの色
                        .border(1.dp, Color(0xFF1E1F22))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentBuffer = logcatLines.size
                    val maxBuffer = appSettings.logcatBufferSize
                    val filteredCount = filteredLogs.size
                    val oldestTime = logcatLines.firstOrNull { it.timestamp.isNotBlank() }?.timestamp ?: "--:--:--.---"

                    // 左側: バッファ使用量
                    Text(
                        text = "Buffer: $currentBuffer / $maxBuffer",
                        fontSize = 12.sp,
                        color = if (currentBuffer >= maxBuffer) Color(0xFFFFC66D) else Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )

                    // 中央: フィルタリング後の表示件数
                    Text(
                        text = "Showing: $filteredCount items",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )

                    // 右側: 最古のログ時刻
                    Text(
                        text = "Oldest: $oldestTime",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}