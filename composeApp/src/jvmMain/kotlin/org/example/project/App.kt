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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import org.koin.compose.koinInject
import java.io.File
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.compose.resources.painterResource
import testbed_core.composeapp.generated.resources.Res
import testbed_core.composeapp.generated.resources.ic_sheep

@Composable
@Preview
fun App() {
    val viewModel: MainViewModel = viewModel { MainViewModel() }
    val toolViewModel: ToolViewModel = koinInject()
    val logLines = remember { mutableStateListOf<LogLine>() }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDeviceInfoDialog by remember { mutableStateOf(false) }
    val appSettings by viewModel.appSettings.collectAsState() // ★設定を監視

    LaunchedEffect(viewModel) {

        if (viewModel.appSettings.value.autoOpenLogcat) {
            toolViewModel.openWindow()
        }
        viewModel.logFlow.collect { log ->

            logLines.add(log)
            if (logLines.size > 2000) {
                logLines.removeRange(0, logLines.size - 2000)
            }
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val isLogcatWindowOpen by toolViewModel.isToolWindowOpen.collectAsState()
    val scope = rememberCoroutineScope()
    val drawerState =
        androidx.compose.material3.rememberDrawerState(initialValue =
            androidx.compose.material3.DrawerValue.Closed)
        //rememberDrawerState(initialValue = DrawerValue.Closed)

    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                        onRunTest = { plugin, methodName ->
                            viewModel.runTest(plugin, methodName)
                            scope.launch { drawerState.close() }
                        },
                        onCloseRequest = {
                            scope.launch { drawerState.close() }
                        },
                        onOpenResultsClick = {
                            viewModel.openResultsDirectory()
                        },
                        onImportPluginClick = { file ->
                            viewModel.importPluginZip(file)
                        }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopControlBar(
                        adbConnected = uiState.adbIsValid,
                        adbUnauthorized = uiState.isUnauthorized,
                        deviceSerial = uiState.deviceSerial,
                        isRunning = uiState.isRunning,
                        testPlugins = viewModel.testPlugins,
                        onDeviceInfoClick= {showDeviceInfoDialog=true},
                        onRunTest = { viewModel.runTest(it) },
                        onRefreshPlugins = { viewModel.refreshPlugins() },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSetupAgentClick = { viewModel.setupMuttonAgent() },
                        onLogcatClick = { toolViewModel.openWindow() }
                    )
                },
                content = { padding ->
                    Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                        LogConsole(logs = logLines, modifier = Modifier.weight(1f))
                        UtilitySideBar(
                            onSettingsClick = { showSettingsDialog = true }
                        )
                    }
                }
            )
        }
        
        // Snackbarを最前面に表示
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    if (showSettingsDialog) {
            SettingsDialog(
                currentSettings = appSettings,
                onDismiss = { showSettingsDialog = false },
                onSave = { newSettings ->
                    viewModel.saveSettings(newSettings)
                    showSettingsDialog = false
                },
                onReinstallAgent = { viewModel.reinstallMuttonAgent() }
            )
        }
        if (showDeviceInfoDialog) {
            DeviceInfoDialog(
                infoText = uiState.deviceInfo,
                onDismiss = { showDeviceInfoDialog = false }
            )
        }
        if (isLogcatWindowOpen) {
            ToolWindow(viewModel = toolViewModel, onCloseRequest = { toolViewModel.closeWindow() })
        }
    }
}


@Composable
fun TestListDrawerContent(
    testPlugins: List<TestPlugin>,
    onRunTest: (TestPlugin, String?) -> Unit,
    onCloseRequest: () -> Unit,
    onOpenResultsClick: () -> Unit,
    onImportPluginClick: (java.io.File) -> Unit
) {
    // カテゴリごとにグループ化し、(none)を末尾にするようにソート
    val groupedPlugins by remember {
        derivedStateOf {
            testPlugins.groupBy { it.category }
                .toList()
                .sortedWith { a, b ->
                    val catA = a.first
                    val catB = b.first
                    val isAOthers = catA.isBlank() || catA == "(none)"
                    val isBOthers = catB.isBlank() || catB == "(none)"
                    
                    when {
                        isAOthers && isBOthers -> 0
                        isAOthers -> 1
                        isBOthers -> -1
                        else -> catA.compareTo(catB)
                    }
                }
                .toMap()
        }
    }

    // カテゴリの開閉状態を管理するステート
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    // テスト項目の開閉状態を管理するステート
    val expandedItems = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Test Explorer",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(16.dp))
                
                // Results フォルダを開く
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenResultsClick() }
                ) {
                    Icon(Icons.Default.Folder, contentDescription = "Open Results Folder", tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Results",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                
                Spacer(Modifier.width(16.dp))
                
                // Plugin ZIP をインポート
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        val fileDialog = java.awt.FileDialog(null as java.awt.Frame?, "Select Plugin ZIP", java.awt.FileDialog.LOAD)
                        fileDialog.setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
                        fileDialog.isVisible = true
                        val file = fileDialog.file
                        val dir = fileDialog.directory
                        if (file != null) {
                            onImportPluginClick(java.io.File(dir, file))
                        }
                    }
                ) {
                    Icon(Icons.Default.Publish, contentDescription = "Import Plugin ZIP", tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Import",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
            IconButton(onClick = onCloseRequest) {
                Icon(Icons.Default.Close, contentDescription = "Close Menu", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        LazyColumn {
            groupedPlugins.forEach { (category, plugins) ->
                // デフォルトは開いた状態
                val isExpanded = expandedCategories[category] ?: true
                val categoryName = if (category.isBlank() || category == "(none)") "Others" else category

                // カテゴリヘッダー
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedCategories[category] = !isExpanded }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = categoryName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "(${plugins.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                // カテゴリ内のアイテム
                if (isExpanded) {
                    items(plugins) { plugin ->
                        val isItemExpanded = expandedItems[plugin.id] ?: false
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF3C3F41)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 8.dp)
                                .clickable { expandedItems[plugin.id] = !isItemExpanded }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左側: テキスト情報
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = plugin.title.ifBlank { plugin.shortName },
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        
                                        // 開閉状態に応じて文言を切り替え
                                        val displayDesc = if (isItemExpanded) {
                                            plugin.description.ifBlank { "No description available." }
                                        } else {
                                            if (plugin.description.length > 60) {
                                                plugin.description.take(60) + "..."
                                            } else {
                                                plugin.description.ifBlank { "No description available." }
                                            }
                                        }
                                        
                                        Text(
                                            text = displayDesc,
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                        
                                        if (isItemExpanded) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "Class: ${plugin.className}",
                                                color = Color.Gray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    
                                    Spacer(Modifier.width(8.dp))
                                    
                                    // 右側: 実行ボタン（やや大きめ）
                                    Button(
                                        onClick = { onRunTest(plugin, null) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF569CD6)),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text("Run All", color = Color.White)
                                    }
                                }
                                
                                // 個別テスト実行用プルダウン
                                if (plugin.methods.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        var selectedMethod by remember { mutableStateOf(plugin.methods.first()) }
                                        var expanded by remember { mutableStateOf(false) }
                                        
                                        Box(modifier = Modifier.weight(1f)) {
                                            OutlinedTextField(
                                                value = selectedMethod,
                                                onValueChange = {},
                                                readOnly = true,
                                                label = { Text("Single Test", color = Color.Gray, fontSize = 10.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White,
                                                    focusedBorderColor = Color(0xFF569CD6),
                                                    unfocusedBorderColor = Color.Gray,
                                                    focusedContainerColor = Color.Transparent,
                                                    unfocusedContainerColor = Color.Transparent
                                                ),
                                                trailingIcon = {
                                                    Icon(
                                                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                        contentDescription = null,
                                                        tint = Color.White
                                                    )
                                                }
                                            )
                                            // Overlay to catch clicks
                                            Box(
                                                modifier = Modifier
                                                    .matchParentSize()
                                                    .clickable { expanded = !expanded }
                                            )
                                            DropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false },
                                                modifier = Modifier.background(Color(0xFF3C3F41))
                                            ) {
                                                plugin.methods.forEach { method ->
                                                    DropdownMenuItem(
                                                        text = { Text(method, color = Color.White) },
                                                        onClick = {
                                                            selectedMethod = method
                                                            expanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        
                                        Spacer(Modifier.width(8.dp))
                                        
                                        Button(
                                            onClick = { onRunTest(plugin, selectedMethod) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A8759)),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            Text("Run", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// composeApp/src/jvmMain/kotlin/org/example/project/App.kt

// ... imports ...

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopControlBar(
    adbConnected: Boolean,
    adbUnauthorized: Boolean,
    deviceSerial: String,
    isRunning: Boolean,
    testPlugins: List<TestPlugin>,
    onRunTest: (TestPlugin) -> Unit,
    onDeviceInfoClick: () -> Unit,
    onRefreshPlugins: () -> Unit,
    onMenuClick: () -> Unit,
    onSetupAgentClick: () -> Unit,
    onLogcatClick: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF2B2D30),
            titleContentColor = Color.White,
            actionIconContentColor = Color.White
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左側: Menu Button
                TooltipIconButton(
                    icon = Icons.Default.Menu,
                    tooltip = "Open Test Menu",
                    tint = Color.White,
                    enabled = !isRunning, // ★実行中は無効化
                    onClick = onMenuClick
                )

                Text("Test Explorer", fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))

                Spacer(Modifier.width(4.dp))

                // 左側: Refresh Button
                TooltipIconButton(
                    icon = Icons.Default.Refresh,
                    tooltip = "Reload Plugins",
                    // 実行中はグレー、通常時は明るいグレー
                    tint = if (isRunning) Color.Gray else Color(0xFFCCCCCC),
                    enabled = !isRunning,
                    onClick = onRefreshPlugins
                )

                Spacer(Modifier.width(12.dp))
                // ... (セパレータや端末ステータス表示はそのまま) ...
                Divider(Modifier.height(24.dp).width(1.dp), color = Color.Gray)
                Spacer(Modifier.width(12.dp))


                val deviceStatusColor = when {
                    adbUnauthorized -> Color(0xFFFFC66D) // 警告の黄色 (Warning)
                    adbConnected -> Color(0xFF6B9F78)    // 正常の緑 (Active)
                    else -> Color.Gray                   // 切断のグレー
                }

                val deviceStatusText = when {
                    adbUnauthorized -> "Unauthorized ($deviceSerial)"
                    adbConnected -> deviceSerial.ifEmpty { "Connected" } // ★ シリアル番号を表示
                    else -> "Disconnected"
                }
                Icon(Icons.Default.PhoneAndroid, null, tint =deviceStatusColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))

                Text(
                    text = deviceStatusText,
                    fontSize = 13.sp,
                    color = deviceStatusColor // 文字色もアイコンに合わせる
                )
                if (adbConnected || adbUnauthorized) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDeviceInfoClick, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "Device Info", tint = deviceStatusColor, modifier = Modifier.size(16.dp))
                    }
                }
                Divider(Modifier.height(24.dp).width(1.dp).padding(horizontal = 8.dp), color = Color.Gray)

                // ★追加: 羊（Mutton Agent）デプロイボタン
                TooltipIconButton(
                    onClick = onSetupAgentClick,
                    tooltip = "Deploy Mutton Agent",
                    enabled = adbConnected
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_sheep),
                        contentDescription = "Deploy Mutton Agent",
                        tint = if (adbConnected) Color(0xFF569CD6) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (isRunning) {
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF569CD6))
                }
            }
        },
        actions = {
            // 右側: Open ToolBox
            TextButton(onClick = onLogcatClick) {
                Icon(Icons.Default.HomeRepairService, contentDescription = "Open ToolBox", tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("Open ToolBox", color = Color.White)
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
                    items(filteredLogs, key = { it.id }) { log ->
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
    onSettingsClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxHeight().width(56.dp).background(Color(0xFF2B2D30)).drawWithContent {
        drawContent()
        drawLine(color = Color(0xFF1E1F22), start = Offset(0f, 0f), end = Offset(0f, size.height), strokeWidth = 1.dp.toPx())
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
        Spacer(Modifier.weight(1f))
        TooltipIconButton(Icons.Default.Settings, "Settings") { onSettingsClick() }
        Spacer(Modifier.height(16.dp))
    }
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
    onSave: (AppSettings) -> Unit,
    onReinstallAgent: () -> Unit
) {
    // ダイアログ内のローカルステート
    var autoOpen by remember { mutableStateOf(currentSettings.autoOpenLogcat) }
    var bufferSizeText by remember { mutableStateOf(currentSettings.logcatBufferSize.toString()) }
    var pastMinutesText by remember { mutableStateOf(currentSettings.logcatPastMinutes.toString()) }
    var mcpHost by remember { mutableStateOf(currentSettings.mcpServerHost) }
    var useFallback by remember { mutableStateOf(currentSettings.useMcpFallback) }

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Logcat", "MCP")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D30)),
            modifier = Modifier.padding(16.dp).width(400.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(16.dp))

                // タブヘッダー
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color(0xFF2B2D30),
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = Color(0xFF569CD6)
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title, color = if (selectedTabIndex == index) Color(0xFF569CD6) else Color.Gray) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // タブコンテンツ
                when (selectedTabIndex) {
                    0 -> {
                        // Logcat 設定
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = autoOpen,
                                    onCheckedChange = { autoOpen = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF569CD6))
                                )
                                Text("Open ToolBox window on startup", color = Color.White, fontSize = 14.sp)
                            }

                            Spacer(Modifier.height(16.dp))

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

                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = pastMinutesText,
                                onValueChange = { if (it.isEmpty() || it.all { char -> char.isDigit() }) pastMinutesText = it },
                                label = { Text("Logcat Past Fetch (minutes)", color = Color.Gray) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF569CD6),
                                    unfocusedBorderColor = Color.Gray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    1 -> {
                        // MCP 設定
                        Column {
                            Text("MCP Server Host", color = Color.Gray, fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = (mcpHost == "::"),
                                    onClick = { mcpHost = "::" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF569CD6))
                                )
                                Text(":: (IPv6/v4 All Interfaces)", color = Color.White, fontSize = 14.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = (mcpHost == "::1"),
                                    onClick = { mcpHost = "::1" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF569CD6))
                                )
                                Text("::1 (IPv6 Localhost)", color = Color.White, fontSize = 14.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = (mcpHost == "0.0.0.0"),
                                    onClick = { mcpHost = "0.0.0.0" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF569CD6))
                                )
                                Text("0.0.0.0 (IPv4 All Interfaces)", color = Color.White, fontSize = 14.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = (mcpHost == "127.0.0.1" || mcpHost == "localhost"),
                                    onClick = { mcpHost = "127.0.0.1" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF569CD6))
                                )
                                Text("127.0.0.1 (IPv4 Localhost)", color = Color.White, fontSize = 14.sp)
                            }

                            Spacer(Modifier.height(16.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = useFallback,
                                    onCheckedChange = { useFallback = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF569CD6))
                                )
                                Text("Enable MCP Fallback (for single client)", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ボタン類（フッター）
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onReinstallAgent) {
                        Text("Reinstall Agent", color = Color(0xFF569CD6))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val bufferSize = bufferSizeText.toIntOrNull() ?: currentSettings.logcatBufferSize
                            val pastMinutes = pastMinutesText.toIntOrNull() ?: currentSettings.logcatPastMinutes
                            onSave(AppSettings(autoOpen, bufferSize, pastMinutes, mcpHost, useFallback))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF569CD6))
                    ) {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}
@Composable
fun DeviceInfoDialog(infoText: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D30)),
            modifier = Modifier.padding(16.dp).width(300.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Device Information", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                // ★ SelectionContainer で囲むことでマウスでテキスト選択＆コピペ可能になる
                SelectionContainer {
                    Text(
                        text = infoText.ifEmpty { "No information available." },
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color(0xFF569CD6))
                    }
                }
            }
        }
    }
}