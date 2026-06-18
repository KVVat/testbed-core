package org.example.project

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.example.project.adb.AdbRepository
import org.example.project.adb.LogEvent
import org.example.project.adb.AdbFile
import org.example.project.adb.PreviewData
import org.example.project.model.UiNode
import org.example.project.model.DumpResult
import org.example.project.model.TimelineItem
import org.example.project.model.Snapshot
import org.example.project.model.ActionDetails
import org.example.project.tools.LogRecorder
import org.example.project.tools.LogcatParser
import org.example.project.tools.ProcessNameResolver
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import com.google.gson.Gson
import org.jetbrains.skia.Image
import java.util.Base64


data class LayoutHistoryItem(
    val timestamp: String,
    val jsonFile: File,
    val pngFile: File?,
    val displayTime: String,
    val uuid: String,
    val tag: String?
)

class ToolViewModel : ViewModel(), KoinComponent {
    private val adbRepository: AdbRepository by inject()

    // File Explorer Pin/Bookmark properties & operations
    private val defaultBookmarks = listOf(
        "/data/local/tmp",
        "/data/system",
        "/sdcard",
        "/data/data"
    )

    private var isInitialPathLoaded = false

    private val _pinnedPaths = MutableStateFlow<List<String>>(emptyList())
    val pinnedPaths = _pinnedPaths.asStateFlow()

    private val settingsFile: File get() = File(baseDir, "app_settings.properties")

    private val _splitPercent = MutableStateFlow(0.6f)
    val splitPercent = _splitPercent.asStateFlow()

    private val _isToolWindowOpen = MutableStateFlow(false)
    val isToolWindowOpen = _isToolWindowOpen.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: Logcat, 1: UI Inspector
    val selectedTab = _selectedTab.asStateFlow()

    // Logcat properties
    private val _logcatLines = mutableStateListOf<LogLine>()
    val logcatLines: List<LogLine> get() = _logcatLines

    private val _logcatFilter = MutableStateFlow("")
    val logcatFilter = _logcatFilter.asStateFlow()

    // UI Inspector properties
    private val _uiDumpRoot = MutableStateFlow<UiNode?>(null)
    val uiDumpRoot = _uiDumpRoot.asStateFlow()

    private val _uiDumpScreenshot = MutableStateFlow<ImageBitmap?>(null)
    val uiDumpScreenshot = _uiDumpScreenshot.asStateFlow()

    private val _inspectorMode = MutableStateFlow(0) // 0: Touch/Tap, 1: Select Area, 2: History
    val inspectorMode = _inspectorMode.asStateFlow()
    private val _uiDumpLoadingState = MutableStateFlow("Waiting for automatic polling...")
    val uiDumpLoadingState = _uiDumpLoadingState.asStateFlow()
    private val _activeLayoutTime = MutableStateFlow("")
    val activeLayoutTime = _activeLayoutTime.asStateFlow()

    fun openSavedLayoutsFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val savedDir = File(baseDir, "saved_layouts")
            if (!savedDir.exists()) {
                savedDir.mkdirs()
            }
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(savedDir)
                } else {
                    log("SYSTEM", "Desktop is not supported on this platform", LogLevel.WARN)
                }
            } catch (e: Exception) {
                log("SYSTEM", "Failed to open saved layouts directory: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun openUiInspector() {
        openWindow()
        setTab(1)
    }
    fun setInspectorMode(mode: Int) {
        val prev = _inspectorMode.value
        _inspectorMode.value = mode
        if (mode == 0 && prev != 0) {
            log("Agent", "Swapping back to Interaction mode: forcing active UI dump fetch", LogLevel.INFO)
            viewModelScope.launch {
                dumpMuttonAgent(silent = true, force = true)
            }
        }
    }

    private val _showSimpleTree = MutableStateFlow(true)
    val showSimpleTree = _showSimpleTree.asStateFlow()

    fun toggleSimpleTree() {
        _showSimpleTree.value = !_showSimpleTree.value
    }

    private val _snackbarMessage = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    fun showSnackbar(message: String) {
        viewModelScope.launch {
            _snackbarMessage.emit(message)
        }
    }

    private val _uiDumpScreenWidth = MutableStateFlow(1080)
    val uiDumpScreenWidth = _uiDumpScreenWidth.asStateFlow()

    private val _uiDumpScreenHeight = MutableStateFlow(2400)
    val uiDumpScreenHeight = _uiDumpScreenHeight.asStateFlow()

    // Timeline Inspector properties
    private val _timelineItems = MutableStateFlow<List<TimelineItem>>(emptyList())
    val timelineItems = _timelineItems.asStateFlow()

    private val _selectedTimelineIndex = MutableStateFlow(-1)
    val selectedTimelineIndex = _selectedTimelineIndex.asStateFlow()

    private var uiPollingJob: kotlinx.coroutines.Job? = null
    private var lastRawScreenshotBase64: String = ""



    private val baseDir: File get() = if (JUnitBridge.baseDir.isNotBlank()) File(JUnitBridge.baseDir) else File(".")

    var logcatBufferSize: Int = 30000

    init {
        loadPinnedPaths()
        viewModelScope.launch {
            adbRepository.logcatLines.collect { line ->
                onLogcatReceived(line)
            }
        }
        viewModelScope.launch {
            adbRepository.logcatFlush.collect {
                flushLogcatBuffer()
            }
        }
        viewModelScope.launch {
            adbRepository.adbState.collect { state ->
                if (state.isValid && _isToolWindowOpen.value) {
                    startLogcat()
                    if (!isInitialPathLoaded) {
                        val lastPin = _pinnedPaths.value.firstOrNull() ?: "/"
                        if (lastPin != "/") {
                            navigateTo(lastPin)
                        } else {
                            refreshFileList()
                        }
                        isInitialPathLoaded = true
                    } else {
                        refreshFileList()
                    }
                } else if (!state.isValid) {
                    stopLogcat()
                    _uiDumpLoadingState.value = "Device disconnected. Checking connection..."
                }
            }
        }
    }

    fun setTab(index: Int) {
        _selectedTab.value = index
        if (index == 1) {
            startUiPolling()
        } else {
            stopUiPolling()
        }
    }

    fun openWindow() {
        _isToolWindowOpen.value = true
        startLogcat()
        refreshFileList()
        if (_selectedTab.value == 1) {
            startUiPolling()
        }
    }

    fun openFileExplorer() {
        _selectedTab.value = 2
        openWindow()
    }

    fun closeWindow() {
        _isToolWindowOpen.value = false
        stopLogcat()
        stopUiPolling()
    }

    private fun startUiPolling() {
        stopUiPolling()
        uiPollingJob = viewModelScope.launch {
            // Initial load
            dumpMuttonAgent(silent = true)
            while (true) {
                kotlinx.coroutines.delay(5000L) // Poll every 5 seconds
                dumpMuttonAgent(silent = true)
            }
        }
    }

    private fun stopUiPolling() {
        uiPollingJob?.cancel()
        uiPollingJob = null
    }


    fun startLogcat() {
        viewModelScope.launch {
            adbRepository.startLogcat()
        }
    }

    fun stopLogcat() {
        viewModelScope.launch {
            adbRepository.stopLogcat()
        }
    }

    fun clearLogcat() {
        _logcatLines.clear()
        viewModelScope.launch { adbRepository.clearLogcatBuffer() }
    }

    fun updateLogcatFilter(text: String) {
        _logcatFilter.value = text
    }

    private var pendingLogLine: LogLine? = null

    fun onLogcatReceived(rawLine: String) {
        // 1. When a new header line arrives
        if (LogcatParser.isHeader(rawLine)) {
            flushPendingLog() // Flush the previous log
            pendingLogLine = LogcatParser.parseHeader(rawLine)
            return
        }

        // 2. When a blank line arrives (log separator)
        if (rawLine.isBlank()) {
            flushPendingLog() // Flush because it's a separator
            return
        }

        // 3. Others (message body, stack trace, etc.)
        pendingLogLine?.let { current ->
            ProcessNameResolver.updateFromLog(current.tag, rawLine)
            val newMessage =
                if (current.message.isEmpty()) rawLine else "${current.message}\n$rawLine"
            pendingLogLine = current.copy(message = newMessage)
        }
    }

    private fun flushPendingLog() {
        pendingLogLine?.let { log ->
            if (log.message.isNotBlank()) {
                _logcatLines.add(log)
                if (_logcatLines.size > logcatBufferSize) {
                    _logcatLines.removeRange(0, _logcatLines.size - logcatBufferSize)
                }
            }
        }
        pendingLogLine = null
    }

    fun flushLogcatBuffer() {
        flushPendingLog()
    }

    fun pingMuttonAgent() {
        viewModelScope.launch {
            adbRepository.pingMuttonAgent()
        }
    }

    fun dumpMuttonAgent(silent: Boolean = false, force: Boolean = false) {
        viewModelScope.launch {
            _uiDumpLoadingState.value = "Fetching layout from mutton-agent..."
            val response = adbRepository.dumpMuttonAgent(includeImage = true, silent = silent)
            if (response != null && response.isNotEmpty()) {
                try {
                    _uiDumpLoadingState.value = "Processing layout and screenshot..."
                    val gson = Gson()
                    val dumpResult = gson.fromJson(response, DumpResult::class.java)
                    if (dumpResult != null && dumpResult.status == "ok") {
                        val newJsonDump = dumpResult.output
                        val newScreenshot = dumpResult.screenshot ?: ""

                        val lastItem = _timelineItems.value.lastOrNull() as? TimelineItem.Record
                        val hasChange = force || lastItem == null || lastItem.snapshot.jsonDump != newJsonDump

                        lastRawScreenshotBase64 = newScreenshot // Cache raw screenshot

                        val timestamp = System.currentTimeMillis()
                        val snapshot = Snapshot(
                            timestamp = timestamp,
                            jsonDump = newJsonDump,
                            screenshotBase64 = newScreenshot
                        )
                        val newRecord = TimelineItem.Record(
                            id = "rec_$timestamp",
                            timestamp = timestamp,
                            snapshot = snapshot,
                            hasChange = hasChange,
                            eventLabel = if (hasChange) "Poll" else null
                        )

                        val currentList = _timelineItems.value.toMutableList()
                        currentList.add(newRecord)
                        if (currentList.size > 36) { // Keep last 3 minutes (36 steps * 5s)
                            currentList.removeAt(0)
                        }
                        _timelineItems.value = currentList
                        _selectedTimelineIndex.value = currentList.size - 1

                        // If state changed or first load, sync active view components
                        if (hasChange) {
                            updateActiveSnapshot(snapshot, dumpResult.screen_width, dumpResult.screen_height)
                            saveArtifact(newScreenshot, newJsonDump)
                        }
                        _uiDumpLoadingState.value = "Waiting for automatic polling..."
                    } else {
                        _uiDumpLoadingState.value = "Failed to fetch UI dump. Retrying..."
                    }
                } catch (e: Exception) {
                    log("Agent", "Failed to parse dump response: ${e.message}", LogLevel.ERROR)
                    _uiDumpLoadingState.value = "Failed to parse UI dump. Retrying..."
                }
            } else {
                _uiDumpLoadingState.value = "Failed to fetch UI dump. Retrying..."
            }
        }
    }

    fun performTap(node: UiNode) {
        if (_isAgentInteracting.value) return
        viewModelScope.launch {
            _isAgentInteracting.value = true
            try {
                val activeRoot = _uiDumpRoot.value ?: return@launch
                val beforeTimestamp = System.currentTimeMillis()
                
                // Generate click coordinates
                val x = (node.bounds.left + node.bounds.right) / 2
                val y = (node.bounds.top + node.bounds.bottom) / 2

                log("Agent", "Simulating tap on element at ($x, $y)", LogLevel.INFO)
                val response = adbRepository.tapCoordinate(x, y)

                if (response != null && response.isNotEmpty()) {
                    try {
                        val gson = Gson()
                        val dumpResult = gson.fromJson(response, DumpResult::class.java)
                        if (dumpResult != null && dumpResult.status == "ok") {
                            val afterTimestamp = System.currentTimeMillis()
                            val afterJsonDump = dumpResult.output
                            val afterScreenshot = dumpResult.screenshot ?: ""

                            lastRawScreenshotBase64 = afterScreenshot // Update cache

                            val afterSnapshot = Snapshot(
                                timestamp = afterTimestamp,
                                jsonDump = afterJsonDump,
                                screenshotBase64 = afterScreenshot
                            )

                            val newRecord = TimelineItem.Record(
                                id = "rec_$afterTimestamp",
                                timestamp = afterTimestamp,
                                snapshot = afterSnapshot,
                                hasChange = true, // Tapping always causes an active event node to be created
                                eventLabel = "Tap",
                                actionDetails = ActionDetails(
                                    command = "tap",
                                    args = mapOf("x" to x, "y" to y)
                                )
                            )

                            val currentList = _timelineItems.value.toMutableList()
                            currentList.add(newRecord)
                            if (currentList.size > 36) { // Keep last 3 minutes (36 steps * 5s)
                                currentList.removeAt(0)
                            }
                            _timelineItems.value = currentList
                            _selectedTimelineIndex.value = currentList.size - 1

                            updateActiveSnapshot(afterSnapshot, dumpResult.screen_width, dumpResult.screen_height)
                        }
                    } catch (e: Exception) {
                        log("Agent", "Failed to parse tap response: ${e.message}", LogLevel.ERROR)
                    }
                }
            } finally {
                _isAgentInteracting.value = false
            }
        }
    }

    fun performCoordinateTap(x: Int, y: Int) {
        if (_isAgentInteracting.value) return
        viewModelScope.launch {
            _isAgentInteracting.value = true
            try {
                log("Agent", "Simulating tap at coordinate ($x, $y)", LogLevel.INFO)
                val response = adbRepository.tapCoordinate(x, y)

                if (response != null && response.isNotEmpty()) {
                    try {
                        val gson = Gson()
                        val dumpResult = gson.fromJson(response, DumpResult::class.java)
                        if (dumpResult != null && dumpResult.status == "ok") {
                            val afterTimestamp = System.currentTimeMillis()
                            val afterJsonDump = dumpResult.output
                            val afterScreenshot = dumpResult.screenshot ?: ""

                            lastRawScreenshotBase64 = afterScreenshot

                            val afterSnapshot = Snapshot(
                                timestamp = afterTimestamp,
                                jsonDump = afterJsonDump,
                                screenshotBase64 = afterScreenshot
                            )

                            val newRecord = TimelineItem.Record(
                                id = "rec_$afterTimestamp",
                                timestamp = afterTimestamp,
                                snapshot = afterSnapshot,
                                hasChange = true,
                                eventLabel = "Tap",
                                actionDetails = ActionDetails(
                                    command = "tap",
                                    args = mapOf("x" to x, "y" to y)
                                )
                            )

                            val currentList = _timelineItems.value.toMutableList()
                            currentList.add(newRecord)
                            if (currentList.size > 36) {
                                currentList.removeAt(0)
                            }
                            _timelineItems.value = currentList
                            _selectedTimelineIndex.value = currentList.size - 1

                            updateActiveSnapshot(afterSnapshot, dumpResult.screen_width, dumpResult.screen_height)
                            saveArtifact(afterScreenshot, afterJsonDump)
                        }
                    } catch (e: Exception) {
                        log("Agent", "Failed to parse coordinate tap response: ${e.message}", LogLevel.ERROR)
                    }
                }
            } finally {
                _isAgentInteracting.value = false
            }
        }
    }

    private fun saveArtifact(screenshotBase64: String, jsonLayout: String) {
        val uuid = org.example.project.model.LayoutDatabase.saveLayoutArtifact(
            jsonLayout = jsonLayout,
            screenshotBase64 = screenshotBase64.ifEmpty { null },
            tag = null
        )
        log("SYSTEM", "Auto-saved layout artifact (UUID: $uuid)", LogLevel.INFO)
        loadLayoutHistory()
    }

    fun selectTimelineItem(item: TimelineItem) {
        val record = item as? TimelineItem.Record ?: return
        if (!record.hasChange) return // Disallow selecting linear time line points that have no changes
        
        val list = _timelineItems.value
        val index = list.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            _selectedTimelineIndex.value = index
            updateActiveSnapshot(record.snapshot, _uiDumpScreenWidth.value, _uiDumpScreenHeight.value)
        }
    }

    private fun updateActiveSnapshot(snapshot: Snapshot, screenWidth: Int, screenHeight: Int) {
        try {
            val gson = Gson()
            val uiNode = gson.fromJson(snapshot.jsonDump, UiNode::class.java)
            _uiDumpRoot.value = uiNode
            _uiDumpScreenWidth.value = screenWidth
            _uiDumpScreenHeight.value = screenHeight

            val timeFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            _activeLayoutTime.value = timeFormatter.format(java.util.Date(snapshot.timestamp))

            if (snapshot.screenshotBase64.isNotEmpty()) {
                val bytes = Base64.getDecoder().decode(snapshot.screenshotBase64)
                val skiaImage = Image.makeFromEncoded(bytes)
                _uiDumpScreenshot.value = skiaImage.toComposeImageBitmap()
            } else {
                _uiDumpScreenshot.value = null
            }
        } catch (e: Exception) {
            _uiDumpScreenshot.value = null
        }
    }

    fun pressHardwareKey(keycode: String) {
        if (_isAgentInteracting.value) return
        viewModelScope.launch {
            _isAgentInteracting.value = true
            try {
                log("Agent", "Pressing hardware key: $keycode", LogLevel.INFO)
                val response = adbRepository.pressKey(keycode)
                delay(500L)
                dumpMuttonAgent(silent = true)
            } finally {
                _isAgentInteracting.value = false
            }
        }
    }

    fun saveCurrentLayoutSource(targetDir: File) {
        val currentRecord = _timelineItems.value.lastOrNull() as? TimelineItem.Record
        if (currentRecord == null) {
            showSnackbar("No layout available to export")
            return
        }
        val screenshotBase64 = currentRecord.snapshot.screenshotBase64
        val jsonLayout = currentRecord.snapshot.jsonDump
        
        viewModelScope.launch {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            
            var success = true
            try {
                val jsonFile = File(targetDir, "layout_$timestamp.json")
                jsonFile.writeText(jsonLayout)
                log("SYSTEM", "Layout source exported to: ${jsonFile.absolutePath}", LogLevel.INFO)
            } catch (e: Exception) {
                success = false
                log("SYSTEM", "Failed to export layout source: ${e.message}", LogLevel.ERROR)
            }

            if (screenshotBase64.isNotEmpty()) {
                try {
                    val imgBytes = java.util.Base64.getDecoder().decode(screenshotBase64)
                    val pngFile = File(targetDir, "layout_$timestamp.png")
                    pngFile.writeBytes(imgBytes)
                } catch (e: Exception) {
                    success = false
                    log("SYSTEM", "Failed to export layout screenshot: ${e.message}", LogLevel.ERROR)
                }
            }

            if (success) {
                showSnackbar("Layout source exported successfully")
            } else {
                showSnackbar("Failed to export layout source")
            }
        }
    }


    // UI Inspector nested history properties
    private val _leftPanelMode = MutableStateFlow(0) // 0: UI Tree View, 1: History View
    val leftPanelMode = _leftPanelMode.asStateFlow()

    private val _isAgentInteracting = MutableStateFlow(false)
    val isAgentInteracting = _isAgentInteracting.asStateFlow()

    private val _layoutHistory = MutableStateFlow<List<LayoutHistoryItem>>(emptyList())
    val layoutHistory = _layoutHistory.asStateFlow()

    fun setLeftPanelMode(mode: Int) {
        _leftPanelMode.value = mode
        if (mode == 1) {
            loadLayoutHistory()
        }
    }

    fun loadLayoutHistory() {
        val savedDir = File(baseDir, "saved_layouts")
        if (!savedDir.exists()) {
            _layoutHistory.value = emptyList()
            return
        }
        
        val records = org.example.project.model.LayoutDatabase.getAllRecords()
        val items = records.map { rec ->
            val jsonFile = File(savedDir, rec.jsonFilepath)
            val pngFile = rec.pngFilepath?.let { File(savedDir, it) }
            
            val display = try {
                val instant = java.time.Instant.ofEpochMilli(rec.timestamp)
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                formatter.format(instant)
            } catch (e: Exception) {
                rec.timestamp.toString()
            }
            
            LayoutHistoryItem(
                timestamp = rec.timestamp.toString(),
                jsonFile = jsonFile,
                pngFile = pngFile,
                displayTime = display,
                uuid = rec.uuid,
                tag = rec.tag
            )
        }
        
        _layoutHistory.value = items
    }

    fun selectHistoryItem(item: LayoutHistoryItem) {
        try {
            val jsonLayout = item.jsonFile.readText()
            val gson = Gson()
            val uiNode = gson.fromJson(jsonLayout, UiNode::class.java)
            _uiDumpRoot.value = uiNode
            
            if (item.pngFile != null && item.pngFile.exists()) {
                val bytes = item.pngFile.readBytes()
                val skiaImage = Image.makeFromEncoded(bytes)
                _uiDumpScreenshot.value = skiaImage.toComposeImageBitmap()
            } else {
                _uiDumpScreenshot.value = null
            }
            _activeLayoutTime.value = item.displayTime
        } catch (e: Exception) {
            log("SYSTEM", "Failed to load history item: ${e.message}", LogLevel.ERROR)
            showSnackbar("Failed to load layout history item")
        }
    }


    // File Explorer properties
    private val _currentPath = MutableStateFlow("/")
    val currentPath = _currentPath.asStateFlow()

    private val _fileList = MutableStateFlow<List<AdbFile>>(emptyList())
    val fileList = _fileList.asStateFlow()

    private val _selectedFile = MutableStateFlow<AdbFile?>(null)
    val selectedFile = _selectedFile.asStateFlow()

    private val _previewContent = MutableStateFlow<PreviewData?>(null)
    val previewContent = _previewContent.asStateFlow()

    private val _isTransferring = MutableStateFlow(false)
    val isTransferring = _isTransferring.asStateFlow()

    fun setTransferring(value: Boolean) {
        _isTransferring.value = value
    }

    private val _isRootMode = MutableStateFlow(true)
    val isRootMode = _isRootMode.asStateFlow()

    private var lastValidPath = "/"

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun refreshFileList() {
        viewModelScope.launch {
            try {
                val files = adbRepository.listDirectory(_currentPath.value, _isRootMode.value)
                _fileList.value = files
                lastValidPath = _currentPath.value
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Permission Denied or Failed to access: ${_currentPath.value}\n${e.message}"
                _currentPath.value = lastValidPath
                try {
                    _fileList.value = adbRepository.listDirectory(lastValidPath, _isRootMode.value)
                } catch (_: Exception) {
                    _currentPath.value = "/"
                    lastValidPath = "/"
                    try {
                        _fileList.value = adbRepository.listDirectory("/", _isRootMode.value)
                    } catch (_: Exception) {
                        _fileList.value = emptyList()
                    }
                }
            }
        }
    }

    fun navigateTo(path: String) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        _currentPath.value = normalized
        selectFile(null)
        promoteBookmarkToFirst(normalized)
        refreshFileList()
    }

    fun selectFile(file: AdbFile?) {
        _selectedFile.value = file
        _previewContent.value = null
        cleanupTempEditDir()
        if (file != null && !file.isDirectory && file.size > 0) {
            loadPreview(file.name)
        }
    }

    private val _localEditFilePath = MutableStateFlow<String?>(null)
    val localEditFilePath = _localEditFilePath.asStateFlow()

    private var currentTempEditDir: File? = null

    private fun loadPreview(fileName: String) {
        viewModelScope.launch {
            val current = _currentPath.value
            val fullPath = if (current.endsWith("/")) "$current$fileName" else "$current/$fileName"
            
            cleanupTempEditDir()
            
            try {
                val preview = adbRepository.getFilePreview(fullPath, _isRootMode.value)
                
                val selected = _selectedFile.value
                val isEditableText = selected != null && selected.size <= 4096 && isTextFile(fileName)
                
                if (isEditableText) {
                    val tmpParent = File(System.getProperty("java.io.tmpdir"))
                    val tempDir = File(tmpParent, ".smartedit_${System.currentTimeMillis()}_${(1000..9999).random()}")
                    tempDir.mkdirs()
                    currentTempEditDir = tempDir
                    
                    val localFile = File(tempDir, fileName)
                    val pullResult = adbRepository.pullFile(fullPath, localFile.absolutePath, _isRootMode.value)
                    
                    if (pullResult.startsWith("Success")) {
                        val fileContent = localFile.readText(Charsets.UTF_8)
                        _localEditFilePath.value = localFile.absolutePath
                        
                        _previewContent.value = PreviewData(
                            isBinary = false,
                            textContent = fileContent,
                            hexDumpLines = null,
                            fileType = preview.fileType
                        )
                    } else {
                        _previewContent.value = PreviewData(
                            isBinary = false,
                            textContent = "Error: Failed to pull file for editing: $pullResult",
                            hexDumpLines = null,
                            fileType = preview.fileType
                        )
                    }
                } else {
                    _previewContent.value = preview
                }
            } catch (e: Exception) {
                _previewContent.value = PreviewData(false, "Failed to load preview: ${e.message}")
            }
        }
    }

    fun cleanupTempEditDir() {
        _localEditFilePath.value = null
        currentTempEditDir?.let { dir ->
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
        currentTempEditDir = null
    }

    fun toggleRootMode() {
        _isRootMode.value = !_isRootMode.value
        refreshFileList()
    }

    fun pullFile(deviceFilePath: String, hostDestinationPath: String, onComplete: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isTransferring.value = true
            try {
                val result = adbRepository.pullFile(deviceFilePath, hostDestinationPath, _isRootMode.value)
                onComplete(result)
            } catch (e: Exception) {
                onComplete("Error: ${e.message}")
            } finally {
                _isTransferring.value = false
            }
        }
    }

    fun pushFile(hostFilePath: String, deviceDestinationPath: String, onComplete: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isTransferring.value = true
            try {
                val result = adbRepository.pushFile(hostFilePath, deviceDestinationPath, _isRootMode.value)
                onComplete(result)
                refreshFileList()
            } catch (e: Exception) {
                onComplete("Error: ${e.message}")
            } finally {
                _isTransferring.value = false
            }
        }
    }

    fun pushDroppedFiles(files: List<File>, onComplete: (String) -> Unit = {}) {
        viewModelScope.launch {
            files.forEach { file ->
                val fileName = file.name
                val current = _currentPath.value
                val destPath = if (current.endsWith("/")) "$current$fileName" else "$current/$fileName"
                pushFile(file.absolutePath, destPath, onComplete)
            }
        }
    }

    fun navigateUp() {
        val path = _currentPath.value
        if (path == "/") return
        val normalized = if (path.endsWith("/")) path.dropLast(1) else path
        val lastSlash = normalized.lastIndexOf('/')
        val parent = if (lastSlash <= 0) "/" else normalized.substring(0, lastSlash)
        navigateTo(parent)
    }

    private fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        println("[$tag] ${level.name}: $message")
    }

    fun promoteBookmarkToFirst(path: String) {
        val list = _pinnedPaths.value.toMutableList()
        if (list.contains(path)) {
            list.remove(path)
            list.add(0, path)
            _pinnedPaths.value = list
            savePinnedPaths()
        }
    }

    fun togglePinCurrentPath() {
        val current = _currentPath.value
        val list = _pinnedPaths.value.toMutableList()
        if (list.contains(current)) {
            list.remove(current)
        } else {
            list.add(0, current)
            while (list.size > 5) {
                list.removeAt(list.size - 1)
            }
        }
        _pinnedPaths.value = list
        savePinnedPaths()
    }

    private fun savePinnedPaths() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val props = java.util.Properties()
                if (settingsFile.exists()) {
                    props.load(settingsFile.inputStream())
                }
                props.setProperty("fileExplorerBookmarks", _pinnedPaths.value.joinToString(","))
                props.store(settingsFile.outputStream(), "App Settings")
                log("SYSTEM", "Bookmarks saved to ${settingsFile.absolutePath}: ${_pinnedPaths.value}", LogLevel.INFO)
            } catch (e: Exception) {
                log("SYSTEM", "Failed to save bookmarks: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun loadPinnedPaths() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = settingsFile.absolutePath
                if (settingsFile.exists()) {
                    val props = java.util.Properties().apply { load(settingsFile.inputStream()) }
                    val bookmarkStr = props.getProperty("fileExplorerBookmarks")
                    val splitStr = props.getProperty("fileExplorerSplitPercent")
                    
                    val list = if (!bookmarkStr.isNullOrBlank()) {
                        bookmarkStr.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    } else {
                        defaultBookmarks
                    }
                    _pinnedPaths.value = list.take(5)
                    
                    _splitPercent.value = splitStr?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.6f
                    
                    log("SYSTEM", "Bookmarks loaded successfully from $path: ${_pinnedPaths.value}, Split: ${_splitPercent.value}", LogLevel.INFO)
                } else {
                    _pinnedPaths.value = defaultBookmarks.take(5)
                    _splitPercent.value = 0.6f
                    log("SYSTEM", "Settings file not found at $path. Creating default bookmarks: ${_pinnedPaths.value}", LogLevel.INFO)
                    savePinnedPaths()
                }
            } catch (e: Exception) {
                log("SYSTEM", "Failed to load bookmarks: ${e.message}", LogLevel.ERROR)
                _pinnedPaths.value = defaultBookmarks.take(5)
            }
        }
    }

    fun updateSplitPercent(percent: Float) {
        val clamped = percent.coerceIn(0.1f, 0.9f)
        _splitPercent.value = clamped
        saveSplitPercent()
    }

    private fun saveSplitPercent() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val props = java.util.Properties()
                if (settingsFile.exists()) {
                    props.load(settingsFile.inputStream())
                }
                props.setProperty("fileExplorerSplitPercent", _splitPercent.value.toString())
                props.store(settingsFile.outputStream(), "App Settings")
            } catch (_: Exception) {}
        }
    }

    fun deleteFile(fileName: String, onComplete: (String) -> Unit = {}) {
        viewModelScope.launch {
            val current = _currentPath.value
            val fullPath = if (current.endsWith("/")) "$current$fileName" else "$current/$fileName"
            _isTransferring.value = true
            try {
                val result = adbRepository.deleteFile(fullPath, _isRootMode.value)
                onComplete(result)
                if (result.startsWith("Success")) {
                    refreshFileList()
                }
            } catch (e: Exception) {
                onComplete("Error: ${e.message}")
            } finally {
                _isTransferring.value = false
            }
        }
    }

    fun saveEditedFile(newText: String, onComplete: (String) -> Unit = {}) {
        val localPath = _localEditFilePath.value
        val selected = _selectedFile.value
        if (localPath == null || selected == null) {
            onComplete("Error: No local temporary file to save.")
            return
        }
        
        viewModelScope.launch {
            _isTransferring.value = true
            try {
                val localFile = File(localPath)
                localFile.writeText(newText, Charsets.UTF_8)
                
                val current = _currentPath.value
                val deviceFilePath = if (current.endsWith("/")) "$current${selected.name}" else "$current/${selected.name}"
                
                val result = adbRepository.pushFile(localPath, deviceFilePath, _isRootMode.value)
                onComplete(result)
                
                loadPreview(selected.name)
            } catch (e: Exception) {
                onComplete("Error saving: ${e.message}")
            } finally {
                _isTransferring.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanupTempEditDir()
    }
}
