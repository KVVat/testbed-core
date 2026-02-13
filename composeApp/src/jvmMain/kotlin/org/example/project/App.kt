package org.example.project



import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
@Composable
@Preview
fun App() {
    val viewModel: AppViewModel = viewModel { AppViewModel() }
    val logLines = remember { mutableStateListOf<LogLine>() }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val appSettings by viewModel.appSettings.collectAsState() // ★設定を監視

    LaunchedEffect(viewModel) {

        if (viewModel.appSettings.value.autoOpenLogcat) {
            viewModel.openLogcatWindow()
        }
        viewModel.logFlow.collect { log ->

            logLines.add(log)
            if (logLines.size > 2000) {
                logLines.removeRange(0, logLines.size - 2000)
            }
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val isLogcatWindowOpen by viewModel.isLogcatWindowOpen.collectAsState()
    val scope = rememberCoroutineScope()
    val drawerState =
        androidx.compose.material3.rememberDrawerState(initialValue =
            androidx.compose.material3.DrawerValue.Closed)
        //rememberDrawerState(initialValue = DrawerValue.Closed)

    MaterialTheme(colorScheme = darkColorScheme()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                // 2. Drawer の中身（詳細が見れるテスト一覧）
                ModalDrawerSheet(
                    drawerContainerColor = Color(0xFF2B2D30),
                    modifier = Modifier.width(480.dp)
                ) {
                    TestListDrawerContent(
                        testPlugins = viewModel.testPlugins,
                        onRunTest = { plugin ->
                            viewModel.runTest(plugin)
                            scope.launch { drawerState.close() }
                        },
                        onCloseRequest = {
                            scope.launch { drawerState.close() }
                        }

                    )
                }
            }
        ) {
            Scaffold(

                topBar = {
                    TopControlBar(
                        adbConnected = uiState.adbIsValid,
                        isRunning = uiState.isRunning,
                        testPlugins = viewModel.testPlugins,
                        onRunTest = { viewModel.runTest(it) },
                        onRefreshPlugins = { viewModel.refreshPlugins() },
                        onBackClick = { viewModel.pressBack() },
                        onHomeClick = { viewModel.pressHome() },
                        onSendText = { text -> viewModel.sendText(text) },
                        onScreenshotClick = { viewModel.captureScreenshot() },
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
                },
                content = { padding ->
                    Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                        LogConsole(logs = logLines, modifier = Modifier.weight(1f))
                        UtilitySideBar(
                            onLogcatClick = { enable: Boolean ->
                                if (enable) viewModel.openLogcatWindow() else viewModel.closeLogcatWindow()
                            },
                            onFileExplorerClick = { },
                            onFlashClick = { file: File -> /* viewModel.batchFlashBootImage(file) */ },
                            onClearDataClick = { viewModel.clearAppData() },
                            onSettingsClick = { showSettingsDialog = true }
                        )
                    }
                }
            )
        }
        if (showSettingsDialog) {
            SettingsDialog(
                currentSettings = appSettings,
                onDismiss = { showSettingsDialog = false },
                onSave = { newSettings ->
                    viewModel.saveSettings(newSettings)
                    showSettingsDialog = false
                }
            )
        }
        if (isLogcatWindowOpen) {
            LogcatWindow(viewModel = viewModel, onCloseRequest = { viewModel.closeLogcatWindow() })
        }
    }
}
@Composable
fun TestListDrawerContent(
    testPlugins: List<TestPlugin>,
    onRunTest: (TestPlugin) -> Unit,
    onCloseRequest: () -> Unit // ← これを追加（×ボタンや項目選択時に呼ぶ）
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, // 端に寄せる
            verticalAlignment = Alignment.CenterVertically,

        ) {
            Text(
                text = "Test Selector",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            // ×ボタン
            IconButton(onClick = onCloseRequest) {
                Icon(Icons.Default.Close, contentDescription = "Close Menu",tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(testPlugins) { plugin ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(plugin.name, fontWeight = FontWeight.Bold)
                            // 詳細情報の例：IDや説明文を表示
                            Text("ID: ${plugin.id}", fontSize = 11.sp, color = Color.Gray)
                        }
                    },
                    selected = false,
                    onClick = { onRunTest(plugin) },
                    icon = { Icon(Icons.Default.Science, null) },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopControlBar(
    adbConnected: Boolean,
    isRunning: Boolean,
    testPlugins: List<TestPlugin>,
    onRunTest: (TestPlugin) -> Unit,
    onRefreshPlugins: () -> Unit, // <--- New Parameter
    onBackClick: () -> Unit,
    onHomeClick: () -> Unit,
    onSendText: (String) -> Unit,
    onScreenshotClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF2B2D30), // backgroundColor の代わり
            titleContentColor = Color.White,    // contentColor の代わり
            actionIconContentColor = Color.White
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {

                    IconButton(onClick = onMenuClick, enabled = !isRunning) {
                        Icon(Icons.Default.Menu, contentDescription = "Open Test Menu")
                    }
                    Text("Test Explorer", fontSize = 16.sp)
                    // ...
                }


                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onRefreshPlugins,
                    enabled = !isRunning
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload Plugins",
                        tint = if (isRunning) Color.Gray else Color(0xFFCCCCCC)
                    )
                }

                Spacer(Modifier.width(12.dp))
                Divider(Modifier.height(24.dp).width(1.dp), color = Color.Gray)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Default.PhoneAndroid, null, tint = if (adbConnected) Color(0xFF6B9F78) else Color.Gray, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = if (adbConnected) "Pixel 8 (Active)" else "Disconnected", fontSize = 13.sp, color = Color.LightGray)
                if (isRunning) {
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF569CD6))
                }
            }
        },
        actions = {
            IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, "Back") }
            IconButton(onClick = onHomeClick) { Icon(Icons.Default.Home, "Home") }
            Divider(Modifier.height(24.dp).width(1.dp).padding(horizontal = 8.dp), color = Color.Gray)
            var showInputTextDialog by remember { mutableStateOf(false) }
            IconButton(onClick = { showInputTextDialog = true }) { Icon(Icons.Default.Keyboard, "Input Text") }
            Divider(Modifier.height(24.dp).width(1.dp).padding(horizontal = 8.dp), color = Color.Gray)
            IconButton(onClick = onScreenshotClick) { Icon(Icons.Default.CameraAlt, "Screenshot") }

            if (showInputTextDialog) {
                InputTextDialog(
                    onDismiss = { showInputTextDialog = false },
                    onSend = { text -> onSendText(text); showInputTextDialog = false }
                )
            }
        }
    )
}

@Composable
fun LogConsole(logs: List<LogLine>, modifier: Modifier = Modifier) {
    // 状態管理
    var filterText by remember { mutableStateOf("") }
    var isPaused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    // フィルタリングロジック (message または tag にヒットするもの)
    val filteredLogs = remember(logs, filterText) {
        if (filterText.isBlank()) logs
        else logs.filter {
            it.message.contains(filterText, ignoreCase = true) ||
                    it.tag.contains(filterText, ignoreCase = true)
        }
    }

    // 自動スクロール (Pausedでなく、かつフィルタ中でない場合のみ)
    LaunchedEffect(logs.size) {
        if (!isPaused && filterText.isEmpty() && logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Column(modifier = modifier.background(Color(0xFF1E1F22))) {
        // --- ツールバー (フィルタ & 操作ボタン) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF3C3F41))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // フィルタ入力欄
            OutlinedTextField(
                value = filterText,
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
                ),
                trailingIcon = {
                    if (filterText.isNotEmpty()) {
                        IconButton(onClick = { filterText = "" }) {
                            Icon(Icons.Default.Close, "Clear", tint = Color.Gray)
                        }
                    }
                }
            )

            Spacer(Modifier.width(8.dp))

            // 一時停止ボタン (自動スクロール防止)
            IconButton(onClick = { isPaused = !isPaused }) {
                Icon(
                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isPaused) "Resume Scroll" else "Pause Scroll",
                    tint = if (isPaused) Color(0xFFFFC66D) else Color.Gray // 停止中は黄色で警告
                )
            }

            // コピーボタン (表示中のログを全てコピー)
            IconButton(onClick = {
                val textToCopy = filteredLogs.joinToString("\n") {
                    "${it.timestamp} ${it.level} [${it.tag}] ${it.message}"
                }
                clipboardManager.setText(AnnotatedString(textToCopy))
            }) {
                Icon(Icons.Default.ContentCopy, "Copy Logs", tint = Color.Gray)
            }
        }

        // --- ログリスト ---
        // SelectionContainerで囲むと、マウスドラッグでの個別選択も可能になります
        // (行数が多いと重くなる場合があるので、動作が重ければ外してください)
        val selectionColor = TextSelectionColors(
            handleColor = Color(0xFF569CD6),
            backgroundColor = Color(0xFF264F78)
        )

        CompositionLocalProvider(LocalTextSelectionColors provides selectionColor) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                ) {
                    items(filteredLogs) { log ->
                        LogLineItem(log)
                    }
                }
            }
        }
    }
}

@Composable
fun LogLineItem(log: LogLine) {
    val levelColor = when(log.level) {
        LogLevel.INFO -> Color(0xFFBBBBBB)
        LogLevel.DEBUG -> Color(0xFF569CD6)
        LogLevel.WARN -> Color(0xFFFFC66D)
        LogLevel.ERROR -> Color(0xFFFF6B68)
        LogLevel.PASS -> Color(0xFF6A8759)
    }
    val levelChar = when(log.level) {
        LogLevel.INFO -> "I"
        LogLevel.DEBUG -> "D"
        LogLevel.WARN -> "W"
        LogLevel.ERROR -> "E"
        LogLevel.PASS -> "P"
    }
    Row(modifier = Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Surface(color = levelColor, shape = RoundedCornerShape(4.dp), modifier = Modifier.size(20.dp).padding(top = 1.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = levelChar, color = Color(0xFF1E1F22), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "${log.timestamp} [${log.tag}] ${log.message}", color = levelColor, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
fun UtilitySideBar(
    onLogcatClick: (Boolean) -> Unit,
    onFileExplorerClick: () -> Unit,
    onFlashClick: (File) -> Unit,
    onClearDataClick: () -> Unit,
    onSettingsClick: () -> Unit
    ) {
    Column(modifier = Modifier.fillMaxHeight().width(56.dp).background(Color(0xFF2B2D30)).drawWithContent {
        drawContent()
        drawLine(color = Color(0xFF1E1F22), start = Offset(0f, 0f), end = Offset(0f, size.height), strokeWidth = 1.dp.toPx())
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
        Spacer(Modifier.height(16.dp))
        var isLogcatRunning by remember { mutableStateOf(false) }
        UtilityIcon(icon = if (isLogcatRunning) Icons.Default.Visibility else Icons.Default.VisibilityOff, tooltip = "Logcat Monitor") {
            isLogcatRunning = !isLogcatRunning
            onLogcatClick(isLogcatRunning)
        }
        Spacer(Modifier.height(24.dp))
        UtilityIcon(Icons.Default.Folder, "File Explorer") { onFileExplorerClick() }
        Spacer(Modifier.height(24.dp))
        UtilityIcon(Icons.Default.FlashOn, "Batch Flash") { onFlashClick(File("boot.img")) }
        Spacer(Modifier.weight(1f))
        UtilityIcon(Icons.Default.DeleteSweep, "Clear App Data") { onClearDataClick() }
        Spacer(Modifier.height(16.dp))
        UtilityIcon(Icons.Default.Settings, "Settings") { onSettingsClick() }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun UtilityIcon(icon: ImageVector, tooltip: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = tooltip, tint = Color.Gray) }
}

@Composable
fun InputTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        // Card の引数を containerColor に変更
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF3C3F41)),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Send Text to Device", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // TextField の colors 指定を Material 3 仕様に変更
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Text...") },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedContainerColor = Color(0xFF2B2D30),
                        unfocusedContainerColor = Color(0xFF2B2D30)
                    )
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onSend(text) }) { Text("Send") }
                }
            }
        }
    }
}

// App.kt に追加
@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    // ダイアログ内のローカルステート（キャンセルされたら破棄するため）
    var autoOpen by remember { mutableStateOf(currentSettings.autoOpenLogcat) }
    var bufferSizeText by remember { mutableStateOf(currentSettings.logcatBufferSize.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D30)),
            modifier = Modifier.padding(16.dp).width(350.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(16.dp))

                // Logcat自動起動チェックボックス
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoOpen,
                        onCheckedChange = { autoOpen = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF569CD6))
                    )
                    Text("Open Logcat window on startup", color = Color.White, fontSize = 14.sp)
                }

                Spacer(Modifier.height(16.dp))

                // バッファサイズ入力（数字のみ許容）
                OutlinedTextField(
                    value = bufferSizeText,
                    onValueChange = { if (it.all { char -> char.isDigit() }) bufferSizeText = it },
                    label = { Text("Logcat Buffer Size (lines)", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF569CD6),
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))

                // アクションボタン
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val newSize = bufferSizeText.toIntOrNull()?.coerceAtLeast(100) ?: 2000
                            onSave(AppSettings(autoOpen, newSize))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF569CD6))
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}