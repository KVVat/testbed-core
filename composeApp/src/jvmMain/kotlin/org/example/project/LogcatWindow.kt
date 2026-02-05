package org.example.project

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
// import androidx.compose.ui.text.font.TextOverflow // 削除
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

@Composable
fun LogcatWindow(viewModel: AppViewModel, onCloseRequest: () -> Unit) {
    val windowState = rememberWindowState(width = 800.dp, height = 600.dp)
    val logcatLines = viewModel.logcatLines // mutableStateListOfは直接アクセスでよい
    val logcatFilter by viewModel.logcatFilter.collectAsState()
    val listState = rememberLazyListState()

    // フィルタリングされたログ
    val filteredLogs = remember(logcatLines, logcatFilter) {
        if (logcatFilter.isBlank()) {
            logcatLines
        } else {
            logcatLines.filter { logcatLine -> // 型を明示
                logcatLine.message.contains(logcatFilter, ignoreCase = true) || 
                logcatLine.tag.contains(logcatFilter, ignoreCase = true) 
            }
        }
    }

    // 自動スクロール
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "Logcat Monitor"
    ) {
        MaterialTheme(colors = darkColors()) {
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {
                // TopBar (Filter and Clear)
                TopAppBar(backgroundColor = Color(0xFF2B2D30)) {
                    TextField(
                        value = logcatFilter,
                        onValueChange = { viewModel.updateLogcatFilter(it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        placeholder = { Text("Filter logcat...", color = Color.Gray) },
                        colors = TextFieldDefaults.textFieldColors(
                            backgroundColor = Color(0xFF3C3F41),
                            textColor = Color.White
                        ),
                        singleLine = true
                    )
                    IconButton(onClick = { viewModel.clearLogcat() }) {
                        Icon(Icons.Default.Clear, "Clear Logcat", tint = Color.White)
                    }
                }

                // Log List
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                        items(filteredLogs) { log ->
                            val color = when (log.level) {
                                LogLevel.ERROR -> Color(0xFFFF6B68)
                                LogLevel.DEBUG -> Color(0xFF6A8759)
                                LogLevel.INFO -> Color(0xFFBBBBBB)
                                LogLevel.PASS -> Color(0xFF59A869) 
                            }
                            Text(
                                text = "${log.timestamp} [${log.tag}] ${log.message}",
                                color = color,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                                maxLines = 1 // 単一行表示
                                // overflow = TextOverflow.Ellipsis // 削除
                            )
                        }
                    }
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(scrollState = listState)
                    )
                }
            }
        }
    }
}