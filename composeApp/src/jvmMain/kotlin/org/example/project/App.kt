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
import org.jetbrains.compose.resources.imageResource
import java.io.File

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
            // TopControlBar にアクションを渡す
            topBar = {
                TopControlBar(
                    adbConnected = uiState.adbIsValid,
                    onBackClick = { viewModel.pressBack() },
                    onHomeClick = { viewModel.pressHome() },
                    onSendText = { text -> viewModel.sendText(text) },
                    //onOpenEditorClick = { viewModel.openSmsEditor() },
                    onScreenshotClick = { viewModel.captureScreenshot() }
                )
            },
            content = { padding ->
                Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                    LogConsole(
                        logs = logLines,
                        modifier = Modifier.weight(1f)
                    )

                    // UtilitySideBar は引数が減りました
                    UtilitySideBar(
                        onLogcatClick = { enable ->
                            /*if (enable) {
                                viewModel.openLogcatWindow()
                                viewModel.toggleLogcat(true)
                            } else {
                                viewModel.closeLogcatWindow()
                                viewModel.toggleLogcat(false)
                            }*/
                        },
                        onFileExplorerClick = { /*viewModel.openFileExplorer()*/ },
                        onFlashClick = { file -> viewModel.batchFlashBootImage(file) },
                        onClearDataClick = { viewModel.clearAppData() }
                    )
                }
            }
        )
    }
}

// --- 以下、既存のコンポーザブルをデータ受け取り可能に微修正 ---


@Composable
fun TopControlBar(
    adbConnected: Boolean,
    onBackClick: () -> Unit,
    onHomeClick: () -> Unit,
    onSendText: (String) -> Unit,
    onScreenshotClick: () -> Unit
) {
    TopAppBar(
        backgroundColor = Color(0xFF2B2D30),
        contentColor = Color.White,
        elevation = 0.dp,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = if (adbConnected) Color(0xFF6B9F78) else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (adbConnected) "Pixel 8 (Active)" else "Disconnected",
                    fontSize = 14.sp,
                    color = Color.LightGray
                )
            }
        },
        actions = {
            // --- 右側に配置するアクションボタン群 ---

            // 1. ナビゲーション (Back / Home)
            IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, "Back") }
            IconButton(onClick = onHomeClick) { Icon(Icons.Default.Home, "Home") }

            Divider(Modifier.height(24.dp).width(1.dp).padding(horizontal = 8.dp), color = Color.Gray)

            // 2. 入力系 (エディタ起動 / テキスト送信)
            //IconButton(onClick = onOpenEditorClick) { Icon(Icons.Default.Edit, "Open Editor") }

            var showInputTextDialog by remember { mutableStateOf(false) }
            IconButton(onClick = { showInputTextDialog = true }) { Icon(Icons.Default.Keyboard, "Input Text") }

            Divider(Modifier.height(24.dp).width(1.dp).padding(horizontal = 8.dp), color = Color.Gray)

            // 3. ツール (スクショ)
            IconButton(onClick = onScreenshotClick) { Icon(Icons.Default.CameraAlt, "Screenshot") }

            // テキスト入力ダイアログの制御
            if (showInputTextDialog) {
                InputTextDialog(
                    onDismiss = { showInputTextDialog = false },
                    onSend = {
                        onSendText(it)
                        showInputTextDialog = false
                    }
                )
            }
        }
    )
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

// --- 3. ADBユーティリティバー (右端) ---
@Composable
fun UtilitySideBar(
    onLogcatClick: (Boolean) -> Unit,
    onFileExplorerClick: () -> Unit,
    onFlashClick: (File) -> Unit,
    onClearDataClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(56.dp)
            .background(Color(0xFF2B2D30))
            .drawWithContent {
                drawContent()
                drawLine(
                    color = Color(0xFF1E1F22),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(16.dp))

        // Logcat
        var isLogcatRunning by remember { mutableStateOf(false) }
        UtilityIcon(
            icon = if (isLogcatRunning) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            tooltip = "Logcat Monitor",
            // = if (isLogcatRunning) Color(0xFF6B9F78) else Color.Gray
        ) {
            isLogcatRunning = !isLogcatRunning
            onLogcatClick(isLogcatRunning)
        }

        Spacer(Modifier.height(24.dp))

        // File Explorer
        UtilityIcon(Icons.Default.Folder, "File Explorer") { onFileExplorerClick() }

        Spacer(Modifier.height(24.dp))

        // Flash (Caution)
        UtilityIcon(Icons.Default.FlashOn, "Batch Flash") {
            onFlashClick(File("boot.img"))
        }

        Spacer(Modifier.weight(1f))

        // Clear Data (Danger)
        UtilityIcon(Icons.Default.DeleteSweep, "Clear App Data") {
            onClearDataClick()
        }

        Spacer(Modifier.height(16.dp))
        UtilityIcon(Icons.Default.Settings, "Settings") {}
        Spacer(Modifier.height(16.dp))
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
fun InputTextDialog(onDismiss: () -> Unit,onSend: (String) -> Unit) {
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
                    Button(onClick = { onSend(text) }) {
                        Text("Send")
                    }
                }
            }
        }
    }
}