import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel // 追加: libs.androidx.lifecycle.viewmodelCompose が必要
import org.example.project.AppViewModel
import org.example.project.LogLevel
import org.example.project.LogLine

// --- データモデル ---
data class TestPlugin(val id: String, val name: String, val status: String)

@Composable
@Preview
fun App() {
    // ViewModelの取得 (ここでinitブロックが走り、ADB監視が始まります)
    val viewModel: AppViewModel = viewModel { AppViewModel() }

    // ログフローの監視
    val logLines = remember { mutableStateListOf<LogLine>() }
    LaunchedEffect(viewModel) {
        viewModel.logFlow.collect { log ->
            logLines.add(log)
            // ログが多すぎたら古いものを消すなどの処理もここで可能
        }
    }

    // UIステートの監視 (ボタンの色変えなどに使用)
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme(colors = darkColors()) {
        Scaffold(
            topBar = {
                TopControlBar(
                    // ViewModelの状態を渡す
                    adbConnected = uiState.adbIsValid
                )
            },
            content = { padding ->
                Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                    // ログデータを渡す
                    LogConsole(
                        logs = logLines,
                        modifier = Modifier.weight(1f)
                    )
                    UtilitySideBar()
                }
            }
        )
    }
}

// --- 以下、既存のコンポーザブルをデータ受け取り可能に微修正 ---

@Composable
fun TopControlBar(adbConnected: Boolean = false) { // 引数追加
    // ...
    // デバイス選択ボタンの色を動的に
    Button(
        onClick = { /* ... */ },
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3C3F41)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (adbConnected) "Pixel 8 (Connected)" else "No Device",
            fontSize = 13.sp,
            color = if (adbConnected) Color(0xFF6B9F78) else Color.Gray
        )
    }
    // ...
}

@Composable
fun LogConsole(logs: List<LogLine>, modifier: Modifier = Modifier) { // 引数変更
    val listState = rememberLazyListState()

    // 自動スクロール
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(modifier = modifier.background(Color(0xFF1E1F22)).padding(8.dp)) {
        // ... (Header) ...
        LazyColumn(state = listState) {
            items(logs) { log ->
                // ... (LogLineの表示ロジックは以前と同じ) ...
                val color = when(log.level) {
                    LogLevel.INFO -> Color(0xFFBBBBBB)
                    LogLevel.DEBUG -> Color(0xFF6A8759)
                    LogLevel.ERROR -> Color(0xFFFF6B68)
                    LogLevel.PASS -> Color(0xFF59A869)
                }
                Text(
                    text = "${log.timestamp} [${log.tag}] ${log.message}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

/*
// --- メイン画面 ---
@Composable
@Preview
fun App() {
    MaterialTheme(colors = darkColors()) {
        Scaffold(
            topBar = { TopControlBar() },
            content = { padding ->
                Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                    // 左側: メインログエリア (画面の95%)
                    LogConsole(modifier = Modifier.weight(1f))

                    // 右側: ADBユーティリティバー (画面の5%)
                    UtilitySideBar()
                }
            }
        )
    }
}

// --- 1. 上部ツールバー (Focus-First) ---
@Composable
fun TopControlBar() {
    var showTestDropdown by remember { mutableStateOf(false) }
    var selectedTest by remember { mutableStateOf("FTP_ITC_EXT.1 (TLS)") }

    TopAppBar(
        backgroundColor = Color(0xFF2B2D30), // IntelliJ風ダークグレー
        contentColor = Color.White,
        elevation = 0.dp,
        modifier = Modifier.height(50.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // テスト選択 (Run Configuration style)
            Box {
                Button(
                    onClick = { showTestDropdown = true },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3C3F41)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(selectedTest, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }

                // ドロップダウンメニュー
                DropdownMenu(
                    expanded = showTestDropdown,
                    onDismissRequest = { showTestDropdown = false }
                ) {
                    DropdownMenuItem(onClick = {
                        selectedTest = "FTP_ITC_EXT.1 (TLS)"
                        showTestDropdown = false
                    }) { Text("FTP_ITC_EXT.1 (TLS Check)") }

                    DropdownMenuItem(onClick = {
                        selectedTest = "FCS_CKM.1 (Key Gen)"
                        showTestDropdown = false
                    }) { Text("FCS_CKM.1 (Key Generation)") }

                    Divider()

                    // ストアへの導線
                    DropdownMenuItem(onClick = { /* Open Store Modal */ }) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.Gray)
                        Spacer(Modifier.width(8.dp))
                        Text("+ Add New Test from Store...", color = Color.Gray)
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // デバイス選択
            Button(
                onClick = { /* Select Device */ },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3C3F41)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pixel 8 (Connected)", fontSize = 13.sp, color = Color(0xFF6B9F78)) // Online Green
            }

            Spacer(Modifier.weight(1f)) // 右寄せ用スペーサー

            // 実行コントロール
            IconButton(onClick = { /* Run */ }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color(0xFF59A869), modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { /* Stop */ }) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFE05555))
            }

            Spacer(Modifier.width(16.dp))

            // レポート出力
            IconButton(onClick = { /* Export */ }) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = "Export Report", tint = Color.LightGray)
            }
        }
    }
}

// --- 2. ログコンソールエリア ---
@Composable
fun LogConsole(modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // ダミーログデータ
    val logs = listOf(
        LogLine("10:00:01", "TESTBED", "Starting FTP_ITC_EXT.1...", LogLevel.INFO),
        LogLine("10:00:02", "ADB", "Connected to device serial: 123456", LogLevel.DEBUG),
        LogLine("10:00:03", "TSHARK", "Capture started on interface wlan0", LogLevel.DEBUG),
        LogLine("10:00:05", "TEST", "Step 1: Checking TLS 1.2 support...", LogLevel.INFO),
        LogLine("10:00:06", "VERIFY", "Client Hello found. Ciphers: TLS_ECDHE...", LogLevel.PASS),
        LogLine("10:00:08", "TEST", "Step 2: Connecting to expired.badssl.com", LogLevel.INFO),
        LogLine("10:00:10", "ERROR", "Expected Alert(21) but got nothing. Timeout.", LogLevel.ERROR),
    )

    Column(modifier = modifier.background(Color(0xFF1E1F22)).padding(8.dp)) {
        // ログヘッダー
        Text(
            "Console Output / Wireshark Stream",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(state = listState) {
            items(logs) { log ->
                val color = when(log.level) {
                    LogLevel.INFO -> Color(0xFFBBBBBB)
                    LogLevel.DEBUG -> Color(0xFF6A8759)
                    LogLevel.ERROR -> Color(0xFFFF6B68)
                    LogLevel.PASS -> Color(0xFF59A869)
                }
                Text(
                    text = "${log.timestamp} [${log.tag}] ${log.message}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
*/
// --- 3. ADBユーティリティバー (右端) ---
@Composable
fun UtilitySideBar() {
    var showInputTextDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(50.dp)
            .background(Color(0xFF2B2D30))
            // drawWithContentを使って左側に線を描画します
            .drawWithContent {
                // 最初にコンテンツ（アイコンなど）を描画
                drawContent()
                // 次に左端に線を描画
                drawLine(
                    color = Color(0xFF1E1F22),
                    start = Offset(0f, 0f), // 左上
                    end = Offset(0f, size.height), // 左下
                    strokeWidth = 1.dp.toPx()
                )
            },        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(16.dp))

        // Screenshot Button
        UtilityIcon(Icons.Default.CameraAlt, "Screenshot") {
            // Action: adb shell screencap...
        }

        Spacer(Modifier.height(16.dp))

        // Text Input Button
        UtilityIcon(Icons.Default.Keyboard, "Send Text") {
            showInputTextDialog = true
        }

        Spacer(Modifier.height(16.dp))

        // Clear Data Button
        UtilityIcon(Icons.Default.DeleteSweep, "Clear App Data") {
            // Action: adb shell pm clear...
        }

        Spacer(Modifier.weight(1f))

        UtilityIcon(Icons.Default.Settings, "Settings") {}
        Spacer(Modifier.height(16.dp))
    }

    // テキスト送信ダイアログ（例）
    if (showInputTextDialog) {
        InputTextDialog(onDismiss = { showInputTextDialog = false })
    }
}

@Composable
fun UtilityIcon(icon: ImageVector, tooltip: String, onClick: () -> Unit) {
    // 実際にはTooltipArea等を使うと良い
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = tooltip, tint = Color.Gray)
    }
}

@Composable
fun InputTextDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(8.dp),
            backgroundColor = Color(0xFF3C3F41),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Send Text to Device", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("https://...") },
                    colors = TextFieldDefaults.textFieldColors(
                        textColor = Color.White,
                        cursorColor = Color.White,
                        backgroundColor = Color(0xFF2B2D30)
                    )
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { /* adb shell input text ... */ onDismiss() }) {
                        Text("Send")
                    }
                }
            }
        }
    }
}