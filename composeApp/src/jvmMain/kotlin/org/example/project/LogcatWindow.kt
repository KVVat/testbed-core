package org.example.project

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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
    // 1. ウィンドウの状態管理
    val windowState = rememberWindowState(width = 800.dp, height = 600.dp)

    // 2. データとフィルタの状態取得
    val logcatLines = viewModel.logcatLines // mutableStateListOf
    val logcatFilter by viewModel.logcatFilter.collectAsState()
    val listState = rememberLazyListState()

    // 3. フィルタリングロジック
    // mutableStateListOf の変更を検知させるため、derivedStateOf を使用するのがベストプラクティスですが、
    // 頂いたロジックを活かしつつ、確実に動作するよう構成します。
    // (rememberのkeyにlogcatLines.sizeを含めることで、ログ追加時にもフィルタが再走するようにしています)
    val filteredLogs = remember(logcatLines.size, logcatFilter) {
        if (logcatFilter.isBlank()) {
            logcatLines
        } else {
            logcatLines.filter { logcatLine ->
                logcatLine.message.contains(logcatFilter, ignoreCase = true) ||
                        logcatLine.tag.contains(logcatFilter, ignoreCase = true)
            }
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

                    // フィルタ入力
                    TextField(
                        value = logcatFilter,
                        onValueChange = { viewModel.updateLogcatFilter(it) },
                        // 文字サイズを小さく設定 (12.sp)
                        placeholder = {
                            Text("Filter (tag/msg)", fontSize = 12.sp, color = Color.Gray)
                        },
                        singleLine = true,
                        // 入力文字のスタイルも合わせる
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace // 等幅フォントの方が見やすい場合あり
                        ),
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = Color.White,
                            cursorColor = Color.White,
                            backgroundColor = Color(0xFF1E1F22), // 濃いグレー背景
                            focusedIndicatorColor = Color.Transparent, // 下線を消す
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(4.dp), // 少し角丸に
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 2.dp) // ★上下パディングを限界まで減らす
                            .fillMaxHeight() // TopAppBarの高さいっぱいに広げる
                    )

                    // クリアボタン
                    IconButton(onClick = { viewModel.clearLogcat() }) {
                        Icon(Icons.Default.Clear, "Clear", tint = Color.Gray)
                    }
                }

                // --- ログリスト ---
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(8.dp).fillMaxSize()
                ) {
                    // フィルタ済みのログを表示
                    items(filteredLogs) { log ->
                        // App.kt で定義されている共通コンポーネントを使用
                        LogLineItem(log)
                    }
                }
            }
        }
    }
}
