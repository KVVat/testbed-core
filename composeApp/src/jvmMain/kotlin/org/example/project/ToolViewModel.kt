package org.example.project

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.adb.AdbRepository
import org.example.project.adb.LogEvent
import org.example.project.model.UiNode
import org.example.project.model.DumpResult
import org.example.project.tools.LogRecorder
import org.example.project.tools.LogcatParser
import org.example.project.tools.ProcessNameResolver
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import com.google.gson.Gson
import org.jetbrains.skia.Image
import java.util.Base64

class ToolViewModel : ViewModel(), KoinComponent {
    private val adbRepository: AdbRepository by inject()

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

    private val _uiDumpScreenWidth = MutableStateFlow(1080)
    val uiDumpScreenWidth = _uiDumpScreenWidth.asStateFlow()

    private val _uiDumpScreenHeight = MutableStateFlow(2400)
    val uiDumpScreenHeight = _uiDumpScreenHeight.asStateFlow()

    private val baseDir = if (JUnitBridge.baseDir.isNotBlank()) File(JUnitBridge.baseDir) else File(".")
    private val logRecorder = LogRecorder(baseFileName = File(baseDir, "logcat.log").absolutePath)

    var logcatBufferSize: Int = 30000

    init {
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
                } else if (!state.isValid) {
                    stopLogcat()
                }
            }
        }
    }

    fun setTab(index: Int) {
        _selectedTab.value = index
    }

    fun openWindow() {
        _isToolWindowOpen.value = true
        startLogcat()
    }

    fun closeWindow() {
        _isToolWindowOpen.value = false
        stopLogcat()
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
                viewModelScope.launch(Dispatchers.IO) {
                    logRecorder.write(log)
                }
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

    fun dumpMuttonAgent() {
        viewModelScope.launch {
            val response = adbRepository.dumpMuttonAgent(includeImage = true)
            if (response != null && response.isNotEmpty()) {
                try {
                    val gson = Gson()
                    val dumpResult = gson.fromJson(response, DumpResult::class.java)
                    if (dumpResult != null && dumpResult.status == "ok") {
                        val uiNode = gson.fromJson(dumpResult.output, UiNode::class.java)
                        _uiDumpRoot.value = uiNode
                        _uiDumpScreenWidth.value = dumpResult.screen_width
                        _uiDumpScreenHeight.value = dumpResult.screen_height
                        
                        if (dumpResult.screenshot != null && dumpResult.screenshot.isNotEmpty()) {
                            try {
                                val bytes = Base64.getDecoder().decode(dumpResult.screenshot)
                                val skiaImage = Image.makeFromEncoded(bytes)
                                _uiDumpScreenshot.value = skiaImage.toComposeImageBitmap()
                            } catch (e: Exception) {
                                _uiDumpScreenshot.value = null
                            }
                        } else {
                            _uiDumpScreenshot.value = null
                        }
                    }
                } catch (e: Exception) {
                    // Handle parse error
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        logRecorder.close()
    }
}
