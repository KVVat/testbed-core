package com.securitytool.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ============ データモデル ============

enum class LogLevel {
    INFO,
    DEBUG,
    ERROR,
    PASS
}

data class LogLine(
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: LogLevel
)

// ============ テーマ色（ダークテーマ） ============

private val ColorToolbarBackground = Color(0xFF2B2D30)
private val ColorLogBackground = Color(0xFF1E1F22)
private val ColorConnected = Color(0xFF4CAF50)
private val ColorRun = Color(0xFF4CAF50)
private val ColorStop = Color(0xFFE53935)
private val ColorInfo = Color(0xFF9E9E9E)
private val ColorDebug = Color(0xFF2E7D32)
private val ColorError = Color(0xFFE53935)
private val ColorPass = Color(0xFF81C784)
private val ColorDropdownBorder = Color(0xFF3C3F41)
private val ColorTextPrimary = Color(0xFFBBBBBB)
private val ColorTextSecondary = Color(0xFF8E8E8E)

// ============ ダミーデータ ============

private val dummyLogLines = listOf(
    LogLine("10:23:01.120", "TLS", "Starting TLS handshake...", LogLevel.INFO),
    LogLine("10:23:01.125", "TLS", "Cipher suite: TLS_AES_256_GCM_SHA384", LogLevel.DEBUG),
    LogLine("10:23:01.230", "TLS", "Certificate chain validated.", LogLevel.PASS),
    LogLine("10:23:01.235", "KEY", "Key exchange completed.", LogLevel.INFO),
    LogLine("10:23:01.240", "TLS", "Session established successfully.", LogLevel.PASS),
    LogLine("10:23:02.100", "APP", "Sending test payload...", LogLevel.DEBUG),
    LogLine("10:23:02.150", "APP", "Payload sent (256 bytes).", LogLevel.INFO),
    LogLine("10:23:02.200", "ERR", "Connection timeout on port 443.", LogLevel.ERROR),
    LogLine("10:23:02.205", "TLS", "Retrying with fallback cipher...", LogLevel.DEBUG),
    LogLine("10:23:02.500", "TLS", "Test completed. Result: 3/4 passed.", LogLevel.INFO),
)

private val testConfigurations = listOf(
    "FTP_ITC_EXT.1 (TLS Check)",
    "FCS_CKM.1 (Key Generation)",
)

private const val ADD_NEW_TEST_ITEM = "+ Add New Test from Store..."

// ============ 1. Top Control Bar ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopControlBar(
    selectedConfig: String,
    onConfigSelected: (String) -> Unit,
    deviceName: String,
    isDeviceConnected: Boolean,
    onRunClick: () -> Unit,
    onStopClick: () -> Unit,
    onExportReportClick: () -> Unit,
    onAddNewTestClick: () -> Unit,
) {
    var configDropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(ColorToolbarBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左: テスト構成セレクター
        Box {
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clickable { configDropdownExpanded = true }
                    .border(1.dp, ColorDropdownBorder, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedConfig,
                    color = ColorTextPrimary,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = ColorTextSecondary,
                )
            }
            DropdownMenu(
                expanded = configDropdownExpanded,
                onDismissRequest = { configDropdownExpanded = false },
                modifier = Modifier.background(ColorToolbarBackground),
            ) {
                testConfigurations.forEach { config ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = config,
                                color = ColorTextPrimary,
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            onConfigSelected(config)
                            configDropdownExpanded = false
                        },
                    )
                }
                Divider(color = ColorDropdownBorder)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = ADD_NEW_TEST_ITEM,
                            color = ColorConnected,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        onAddNewTestClick()
                        configDropdownExpanded = false
                    },
                )
            }
        }

        // 中央左: デバイスセレクター
        Text(
            text = deviceName,
            color = if (isDeviceConnected) ColorConnected else ColorTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // 右: アクションボタン
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onRunClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Run",
                    tint = ColorRun,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = onStopClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = ColorStop,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = onExportReportClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = "Export Report",
                    tint = ColorTextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

// ============ 2. Log Console ============

@Composable
private fun LogLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.INFO -> ColorInfo
    LogLevel.DEBUG -> ColorDebug
    LogLevel.ERROR -> ColorError
    LogLevel.PASS -> ColorPass
}

@Composable
private fun LogConsole(logLines: List<LogLine>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorLogBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(logLines) { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = line.timestamp,
                    color = ColorTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "[${line.tag}]",
                    color = LogLevelColor(line.level),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = line.message,
                    color = ColorTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ============ 3. Utility Sidebar ============

@Composable
private fun UtilitySidebar(
    onCameraClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(50.dp)
            .fillMaxHeight()
            .background(ColorToolbarBackground)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onCameraClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Screenshot",
                tint = ColorTextPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(
            onClick = onKeyboardClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = "Send text",
                tint = ColorTextPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(
            onClick = onDeleteClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Clear app data",
                tint = ColorTextPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = ColorTextPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ============ Keyboard テキスト送信ダイアログ ============

@Composable
private fun TextInputDialog(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = ColorToolbarBackground,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Send text to device",
                    color = ColorTextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Enter text...", color = ColorTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ColorTextPrimary,
                        unfocusedTextColor = ColorTextPrimary,
                        cursorColor = ColorConnected,
                        focusedBorderColor = ColorDropdownBorder,
                        unfocusedBorderColor = ColorDropdownBorder,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = ColorTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSend(text)
                            onDismiss()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = ColorConnected,
                        ),
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

// ============ MainScreen ============

@Composable
fun MainScreen() {
    var selectedConfig by remember { mutableStateOf(testConfigurations.first()) }
    var keyboardDialogOpen by remember { mutableStateOf(false) }
    val logLines = remember { dummyLogLines }
    val deviceName = "Pixel 8 (Connected)"
    val isDeviceConnected = true

    Scaffold(
        topBar = {
            TopControlBar(
                selectedConfig = selectedConfig,
                onConfigSelected = { selectedConfig = it },
                deviceName = deviceName,
                isDeviceConnected = isDeviceConnected,
                onRunClick = { /* TODO */ },
                onStopClick = { /* TODO */ },
                onExportReportClick = { /* TODO */ },
                onAddNewTestClick = { /* ストアダイアログを開く */ },
            )
        },
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(ColorLogBackground),
            ) {
                LogConsole(logLines = logLines)
            }
            UtilitySidebar(
                onCameraClick = { /* TODO */ },
                onKeyboardClick = { keyboardDialogOpen = true },
                onDeleteClick = { /* TODO */ },
                onSettingsClick = { /* TODO */ },
            )
        }
    }

    if (keyboardDialogOpen) {
        TextInputDialog(
            onDismiss = { keyboardDialogOpen = false },
            onSend = { text -> /* テキスト送信処理 */ },
        )
    }
}

// ============ Preview ============

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        MainScreen()
    }
}
