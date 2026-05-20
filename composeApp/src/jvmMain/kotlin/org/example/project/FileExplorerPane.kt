package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun FileExplorerPane(viewModel: ToolViewModel) {
    val currentPath by viewModel.currentPath.collectAsState()
    val fileList by viewModel.fileList.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val previewContent by viewModel.previewContent.collectAsState()
    val isTransferring by viewModel.isTransferring.collectAsState()
    val isRootMode by viewModel.isRootMode.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val pinnedPaths by viewModel.pinnedPaths.collectAsState()
    val isPinned = pinnedPaths.contains(currentPath)

    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val splitPercent by viewModel.splitPercent.collectAsState()

    var showPushSuccessMessage by remember { mutableStateOf<String?>(null) }
    var showPullSuccessMessage by remember { mutableStateOf<String?>(null) }
    var isDialogOpen by remember { mutableStateOf(false) }
    var editText by remember(previewContent) { mutableStateOf(previewContent?.textContent ?: "") }

    // Navigation path text field input
    var pathInput by remember(currentPath) { mutableStateOf(currentPath) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {
            // --- Left Side: File Browser ---
            Column(
                    modifier =
                            Modifier.weight(splitPercent)
                                    .fillMaxHeight()
                                    .border(1.dp, Color(0xFF2B2D30))
            ) {
                Row(
                        modifier =
                                Modifier.fillMaxWidth().background(Color(0xFF2B2D30)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.navigateUp() }, enabled = currentPath != "/") {
                        Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Up one level",
                                tint = if (currentPath != "/") Color.White else Color.Gray
                        )
                    }

                    IconButton(onClick = { viewModel.togglePinCurrentPath() }) {
                        Icon(
                                imageVector =
                                        if (isPinned) Icons.Default.Bookmark
                                        else Icons.Default.BookmarkBorder,
                                contentDescription = "Pin Current Path",
                                tint = if (isPinned) Color(0xFF569CD6) else Color.LightGray
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    var dropdownExpanded by remember { mutableStateOf(false) }
                    val autocompleteSuggestions =
                            remember(pinnedPaths, pathInput) {
                                if (pathInput == "/" || pathInput.isBlank()) {
                                    pinnedPaths
                                } else {
                                    pinnedPaths.filter {
                                        it.startsWith(pathInput, ignoreCase = true) &&
                                                it != pathInput
                                    }
                                }
                            }

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                                value = pathInput,
                                onValueChange = {
                                    pathInput = it
                                    dropdownExpanded = true
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                textStyle =
                                        MaterialTheme.typography.bodyMedium.copy(
                                                color = Color.White,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp
                                        ),
                                singleLine = true,
                                colors =
                                        OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFF569CD6),
                                                unfocusedBorderColor = Color(0xFF3C3F41),
                                                cursorColor = Color.White
                                        ),
                                trailingIcon = {
                                    IconButton(onClick = { viewModel.navigateTo(pathInput) }) {
                                        Icon(
                                                imageVector = Icons.Default.ArrowForward,
                                                contentDescription = "Go to path",
                                                tint = Color.LightGray
                                        )
                                    }
                                }
                        )

                        if (autocompleteSuggestions.isNotEmpty()) {
                            DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false },
                                    modifier =
                                            Modifier.fillMaxWidth()
                                                    .background(Color(0xFF2B2D30))
                                                    .border(1.dp, Color(0xFF3C3F41)),
                                    properties =
                                            androidx.compose.ui.window.PopupProperties(
                                                    focusable = false
                                            )
                            ) {
                                autocompleteSuggestions.forEach { suggestion ->
                                    DropdownMenuItem(
                                            text = {
                                                Text(
                                                        suggestion,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontFamily = FontFamily.Monospace
                                                )
                                            },
                                            onClick = {
                                                pathInput = suggestion
                                                viewModel.navigateTo(suggestion)
                                                dropdownExpanded = false
                                            }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(onClick = { viewModel.refreshFileList() }) {
                        Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                            onClick = {
                                val textToCopy =
                                        fileList.joinToString("\n") { file ->
                                            "Name: ${file.name} | Size: ${if (file.isDirectory) "--" else formatSize(file.size)} | Permissions: ${file.permissions} | Modified: ${file.lastModified}"
                                        }
                                clipboardManager.setText(AnnotatedString(textToCopy))
                            },
                            enabled = fileList.isNotEmpty()
                    ) {
                        Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy List",
                                tint = if (fileList.isNotEmpty()) Color.White else Color.Gray
                        )
                    }
                }

                // File List Header
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .background(Color(0xFF2B2D30))
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                "Name",
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(0.5f)
                        )
                        Text(
                                "Size",
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.width(80.dp)
                        )
                        Text(
                                "Permissions",
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.width(100.dp)
                        )
                        Text(
                                "Modified",
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.width(120.dp)
                        )
                    }
                    Divider(color = Color(0xFF3C3F41), thickness = 1.dp)
                }

                // File List Content
                Box(modifier = Modifier.weight(1f)) {
                    if (isTransferring) {
                        Box(
                                modifier = Modifier.fillMaxSize().background(Color(0x801E1F22)),
                                contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = Color(0xFF569CD6)) }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(fileList) { file ->
                                val isSelected = selectedFile == file
                                val backgroundColor =
                                        if (isSelected) Color(0xFF264F78) else Color.Transparent
                                val textColor =
                                        if (file.isDirectory) Color(0xFF569CD6) else Color.White

                                Row(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .background(backgroundColor)
                                                        .combinedClickable(
                                                                onClick = {
                                                                    viewModel.selectFile(file)
                                                                },
                                                                onDoubleClick = {
                                                                    if (file.isDirectory) {
                                                                        val targetPath =
                                                                                if (currentPath
                                                                                                .endsWith(
                                                                                                        "/"
                                                                                                )
                                                                                ) {
                                                                                    "$currentPath${file.name}"
                                                                                } else {
                                                                                    "$currentPath/${file.name}"
                                                                                }
                                                                        viewModel.navigateTo(
                                                                                targetPath
                                                                        )
                                                                    } else if (file.isSymbolicLink &&
                                                                                    file.linkTarget !=
                                                                                            null
                                                                    ) {
                                                                        // Handle symlink navigation
                                                                        // if it points to a
                                                                        // directory
                                                                        // For simplicity, we can
                                                                        // try to navigate if
                                                                        // linkTarget is absolute
                                                                        if (file.linkTarget
                                                                                        .startsWith(
                                                                                                "/"
                                                                                        )
                                                                        ) {
                                                                            viewModel.navigateTo(
                                                                                    file.linkTarget
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                        )
                                                        .padding(
                                                                horizontal = 16.dp,
                                                                vertical = 6.dp
                                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                            imageVector =
                                                    when {
                                                        file.isDirectory -> Icons.Default.Folder
                                                        file.isSymbolicLink ->
                                                                Icons.Default.Shortcut
                                                        else -> Icons.Default.InsertDriveFile
                                                    },
                                            contentDescription = null,
                                            tint =
                                                    when {
                                                        file.isDirectory -> Color(0xFFFFC66D)
                                                        file.isSymbolicLink -> Color(0xFF9876AA)
                                                        else -> Color.LightGray
                                                    },
                                            modifier = Modifier.size(16.dp)
                                    )

                                    Spacer(Modifier.width(8.dp))

                                    Text(
                                            text =
                                                    if (file.isSymbolicLink &&
                                                                    file.linkTarget != null
                                                    ) {
                                                        "${file.name} -> ${file.linkTarget}"
                                                    } else {
                                                        file.name
                                                    },
                                            color = textColor,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(0.5f)
                                    )

                                    Text(
                                            text =
                                                    if (file.isDirectory) "--"
                                                    else formatSize(file.size),
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(80.dp)
                                    )

                                    Text(
                                            text = file.permissions,
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.width(100.dp)
                                    )

                                    Text(
                                            text = file.lastModified,
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.width(120.dp)
                                    )

                                    Spacer(Modifier.width(8.dp))

                                    if (!file.isDirectory) {
                                        var showDeleteConfirm by remember { mutableStateOf(false) }

                                        if (showDeleteConfirm) {
                                            AlertDialog(
                                                    onDismissRequest = {
                                                        showDeleteConfirm = false
                                                    },
                                                    title = {
                                                        Text("Delete File", color = Color.White)
                                                    },
                                                    text = {
                                                        Text(
                                                                "Are you sure you want to delete ${file.name}?",
                                                                color = Color.LightGray
                                                        )
                                                    },
                                                    confirmButton = {
                                                        Button(
                                                                onClick = {
                                                                    showDeleteConfirm = false
                                                                    viewModel.deleteFile(
                                                                            file.name
                                                                    ) { res ->
                                                                        showPullSuccessMessage = res
                                                                    }
                                                                },
                                                                colors =
                                                                        ButtonDefaults.buttonColors(
                                                                                containerColor =
                                                                                        Color(
                                                                                                0xFFFF6B68
                                                                                        )
                                                                        )
                                                        ) { Text("Delete", color = Color.White) }
                                                    },
                                                    dismissButton = {
                                                        TextButton(
                                                                onClick = {
                                                                    showDeleteConfirm = false
                                                                }
                                                        ) { Text("Cancel", color = Color.White) }
                                                    },
                                                    containerColor = Color(0xFF2B2D30)
                                            )
                                        }

                                        IconButton(
                                                onClick = { showDeleteConfirm = true },
                                                modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete File",
                                                    tint = Color(0xFFFF6B68),
                                                    modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.width(24.dp))
                                    }
                                }
                                Divider(color = Color(0xFF2B2D30), thickness = 0.5.dp)
                            }
                        }
                    }
                }

                // Operations Footer / Success Messages & Error messages
                val activeError = errorMessage
                if (activeError != null ||
                                showPushSuccessMessage != null ||
                                showPullSuccessMessage != null
                ) {
                    Box(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .background(Color(0xFF2B2D30))
                                            .padding(8.dp),
                            contentAlignment = Alignment.CenterStart
                    ) {
                        val message =
                                activeError
                                        ?: showPushSuccessMessage ?: showPullSuccessMessage ?: ""
                        val isError =
                                activeError != null ||
                                        (showPushSuccessMessage?.startsWith("Error") ?: false) ||
                                        (showPullSuccessMessage?.startsWith("Error") ?: false)
                        Text(
                                text = message,
                                color = if (isError) Color(0xFFFF6B68) else Color(0xFF6A8759),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                        )
                        IconButton(
                                onClick = {
                                    viewModel.clearErrorMessage()
                                    showPushSuccessMessage = null
                                    showPullSuccessMessage = null
                                },
                                modifier = Modifier.align(Alignment.CenterEnd).size(20.dp)
                        ) {
                            Icon(
                                    Icons.Default.Close,
                                    null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            // --- Draggable Handle (Vertical Divider) ---
            Box(
                    modifier =
                            Modifier.width(6.dp)
                                    .fillMaxHeight()
                                    .background(Color(0xFF2B2D30))
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { change, dragAmount ->
                                            change.consume()
                                            val totalWidthPx =
                                                    this@BoxWithConstraints.constraints.maxWidth
                                                            .toFloat()
                                            if (totalWidthPx > 0) {
                                                val newPercent =
                                                        splitPercent + (dragAmount / totalWidthPx)
                                                viewModel.updateSplitPercent(newPercent)
                                            }
                                        }
                                    }
            ) {
                Divider(
                        modifier = Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight(0.1f),
                        color = Color.Gray
                )
            }

            // --- Right Side: Preview Pane ---
            Column(
                    modifier =
                            Modifier.weight(1f - splitPercent)
                                    .fillMaxHeight()
                                    .background(Color(0xFF1E1F22))
            ) {
                // Preview Header
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .background(Color(0xFF2B2D30))
                                            .padding(8.dp)
                    ) {
                        val headerTitle =
                                remember(selectedFile, previewContent) {
                                    val base = "File Preview (First 2KB)"
                                    val type = previewContent?.fileType
                                    if (type != null) "$base - $type" else base
                                }
                        Text(
                                text = headerTitle,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterStart)
                        )

                        val isEditableText =
                                selectedFile != null &&
                                        !selectedFile!!.isDirectory &&
                                        selectedFile!!.size <= 4096 &&
                                        previewContent != null &&
                                        !previewContent!!.isBinary

                        Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isEditableText && previewContent != null) {
                                IconButton(
                                        onClick = {
                                            viewModel.saveEditedFile(editText) { res ->
                                                showPullSuccessMessage = res
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                            imageVector = Icons.Default.Save,
                                            contentDescription = "Save Changes",
                                            tint = Color(0xFF6A8759),
                                            modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (selectedFile != null && !selectedFile!!.isDirectory) {
                                IconButton(
                                        onClick = {
                                            if (isDialogOpen) return@IconButton
                                            isDialogOpen = true
                                            coroutineScope.launch {
                                                val deviceFilePath =
                                                        if (currentPath.endsWith("/")) {
                                                            "$currentPath${selectedFile!!.name}"
                                                        } else {
                                                            "$currentPath/${selectedFile!!.name}"
                                                        }
                                                val hostPath =
                                                        showSaveFileDialogSafe(
                                                                "Save File to Host",
                                                                selectedFile!!.name
                                                        )
                                                if (hostPath != null) {
                                                    viewModel.pullFile(deviceFilePath, hostPath) {
                                                            res ->
                                                        showPullSuccessMessage = res
                                                    }
                                                }
                                                isDialogOpen = false
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Pull File",
                                            tint = Color(0xFF569CD6),
                                            modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    Divider(color = Color(0xFF3C3F41), thickness = 1.dp)
                }

                // Preview Area
                Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    if (selectedFile == null) {
                        Text(
                                "Select a file to see preview.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (selectedFile!!.isDirectory) {
                        Text(
                                "Directory content preview is not supported.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (selectedFile!!.size == 0L) {
                        Text(
                                "Empty file.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (previewContent == null) {
                        CircularProgressIndicator(
                                color = Color(0xFF569CD6),
                                modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        val preview = previewContent!!
                        val isEditableText =
                                selectedFile != null &&
                                        !selectedFile!!.isDirectory &&
                                        selectedFile!!.size <= 4096 &&
                                        !preview.isBinary

                        if (isEditableText) {
                            OutlinedTextField(
                                    value = editText,
                                    onValueChange = { editText = it },
                                    modifier = Modifier.fillMaxSize(),
                                    textStyle =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                    color = Color(0xFFBBBBBB),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp
                                            ),
                                    colors =
                                            OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color.Transparent,
                                                    unfocusedBorderColor = Color.Transparent,
                                                    cursorColor = Color.White
                                            )
                            )
                        } else {
                            SelectionContainer {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    if (preview.isBinary && preview.hexDumpLines != null) {
                                        item {
                                            Box(
                                                    modifier =
                                                            Modifier.fillMaxWidth()
                                                                    .background(
                                                                            Color(0xFFAAAAAA)
                                                                    ) // Color matched with pane
                                                                    // headers
                                                                    .padding(vertical = 2.dp)
                                            ) {
                                                Text(
                                                        text =
                                                                "ADDR  00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |0123456789ABCDEF|",
                                                        color =
                                                                Color(
                                                                        0xFF333333
                                                                ), // Gold-accent highlight
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Clip
                                                )
                                            }
                                        }
                                        items(preview.hexDumpLines) { line ->
                                            Text(
                                                    text = line,
                                                    color = Color(0xFFBBBBBB),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Clip
                                            )
                                        }
                                    } else {
                                        item {
                                            Text(
                                                    text = preview.textContent ?: "",
                                                    color = Color(0xFFBBBBBB),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp
                                            )
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

// File dialog helper functions
suspend fun showSaveFileDialogSafe(title: String, defaultName: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var result: String? = null
            java.awt.EventQueue.invokeAndWait {
                val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
                dialog.file = defaultName
                dialog.isVisible = true
                val file = dialog.file
                val dir = dialog.directory
                if (file != null && dir != null) {
                    result = File(dir, file).absolutePath
                }
            }
            result
        }

suspend fun showOpenFileDialogSafe(title: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var result: String? = null
            java.awt.EventQueue.invokeAndWait {
                val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                dialog.isVisible = true
                val file = dialog.file
                val dir = dialog.directory
                if (file != null && dir != null) {
                    result = File(dir, file).absolutePath
                }
            }
            result
        }

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

fun isTextFile(name: String): Boolean {
    val textExtensions =
            listOf(
                    ".txt",
                    ".xml",
                    ".json",
                    ".log",
                    ".prop",
                    ".sh",
                    ".conf",
                    ".properties",
                    ".yaml",
                    ".yml",
                    ".ini",
                    ".csv",
                    ".html",
                    ".css",
                    ".js",
                    ".ts",
                    ".kt",
                    ".java",
                    ".gradle"
            )
    val lower = name.lowercase()
    return textExtensions.any { lower.endsWith(it) }
}
