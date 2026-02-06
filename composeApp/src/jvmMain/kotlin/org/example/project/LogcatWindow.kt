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
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

@Composable
fun LogcatWindow(viewModel: AppViewModel, onCloseRequest: () -> Unit) {
    // 1. ウィンドウの状態管理
    val windowState = rememberWindowState(width = 900.dp, height = 600.dp)

    // 2. データとフィルタの状態取得
    val logcatLines = viewModel.logcatLines
    val logcatFilter by viewModel.logcatFilter.collectAsState()

    // ログレベルフィルタの状態（初期値は全選択）
    val selectedLevels = remember { mutableStateListOf(*LogLevel.values()) }
    var expanded by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 3. フィルタリングロジック
    val filteredLogs = remember(logcatLines.size, logcatFilter, selectedLevels.size) {
        logcatLines.filter { logcatLine ->
            val textMatches = if (logcatFilter.isBlank()) true
            else logcatLine.message.contains(logcatFilter, ignoreCase = true) ||
                    logcatLine.tag.contains(logcatFilter, ignoreCase = true)

            val levelMatches = selectedLevels.contains(logcatLine.level)
            textMatches && levelMatches
        }
    }

    // 4. 自動スクロール
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "Logcat Monitor",
    ) {
        MaterialTheme(colors = darkColors()) {
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {

                // --- ツールバー ---
                TopAppBar(
                    backgroundColor = Color(0xFF2B2D30),
                    contentColor = Color.White,
                    elevation = 0.dp
                ) {
                    Spacer(Modifier.width(8.dp))

                    // テキストフィルタ入力
                    TextField(
                        value = logcatFilter,
                        onValueChange = { viewModel.updateLogcatFilter(it) },
                        placeholder = { Text("Filter (tag/msg)", fontSize = 12.sp, color = Color.Gray) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace
                        ),
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = Color.White,
                            backgroundColor = Color(0xFF1E1F22),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp).fillMaxHeight()
                    )

                    Spacer(Modifier.width(8.dp))

                    // --- Multi-Select DropDown Menu ---
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.padding(vertical = 4.dp).fillMaxHeight(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.FilterList, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            // 選択されているレベルを視認しやすく表示
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
                                    onClick = {
                                        if (isSelected) {
                                            if (selectedLevels.size > 1) selectedLevels.remove(level)
                                        } else {
                                            selectedLevels.add(level)
                                        }
                                    }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null, // MenuItemのonClickで処理
                                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF569CD6))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(level.name, color = Color.White, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }

                    // クリアボタン
                    IconButton(onClick = { viewModel.clearLogcat() }) {
                        Icon(Icons.Default.Clear, "Clear", tint = Color.Gray)
                    }
                }

                // --- ログリスト ---
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(8.dp).fillMaxSize()
                    ) {
                        items(filteredLogs) { log ->
                            LogLineItem(log)
                        }
                    }
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(listState)
                    )
                }
            }
        }
    }
}