package org.example.project



import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
@Preview
fun App() {
    val viewModel: AppViewModel = viewModel { AppViewModel() }
    val logLines = remember { mutableStateListOf<LogLine>() }

    LaunchedEffect(viewModel) {
        viewModel.logFlow.collect { log ->
            logLines.add(log)
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
                    modifier = Modifier.width(300.dp)
                ) {
                    TestListDrawerContent(
                        testPlugins = viewModel.testPlugins,
                        onRunTest = { plugin ->
                            viewModel.runTest(plugin)
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
                            onClearDataClick = { viewModel.clearAppData() }
                        )
                    }
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
    onRunTest: (TestPlugin) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Available Tests", style = MaterialTheme.typography.bodySmall, color = Color.White)
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
                    // ハンバーガーアイコン
                    IconButton(onClick = onMenuClick, enabled = !isRunning) {
                        Icon(Icons.Default.Menu, contentDescription = "Open Test Menu")
                    }
                    Text("Test Explorer", fontSize = 16.sp)
                    // ...
                }
                /*
                var testMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(
                        onClick = { testMenuExpanded = true },
                        enabled = !isRunning,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = if (isRunning) Color.Gray else Color(0xFF6A8759))
                        Spacer(Modifier.width(4.dp))
                        Text("Tests", fontSize = 14.sp)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = testMenuExpanded,
                        onDismissRequest = { testMenuExpanded = false },
                        modifier = Modifier.background(Color(0xFF2B2D30))
                    ) {
                        if (testPlugins.isEmpty()) {
                            DropdownMenuItem(onClick = {}) {
                                Text("No Plugins Loaded", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        testPlugins.forEach { plugin ->
                            DropdownMenuItem(onClick = {
                                testMenuExpanded = false
                                onRunTest(plugin)
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Science, null, modifier = Modifier.size(16.dp), tint = Color.LightGray)
                                    Spacer(Modifier.width(8.dp))
                                    Text(plugin.name, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }*/
                // --- INSERT START ---
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
                // --- INSERT END ---
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
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
    Column(modifier = modifier.background(Color(0xFF1E1F22)).padding(8.dp)) {
        LazyColumn(state = listState) { items(logs) { LogLineItem(it) } }
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
fun UtilitySideBar(onLogcatClick: (Boolean) -> Unit, onFileExplorerClick: () -> Unit, onFlashClick: (File) -> Unit, onClearDataClick: () -> Unit) {
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
        UtilityIcon(Icons.Default.Settings, "Settings") {}
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
