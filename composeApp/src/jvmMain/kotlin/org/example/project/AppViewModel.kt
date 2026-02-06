package org.example.project

import java.time.LocalTime
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.adb.AdbObserver
import org.example.project.adb.FastbootClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.withContext
import java.io.IOException

class AppViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState = _uiState.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogLine>(replay = 100)
    val logFlow = _logFlow.asSharedFlow()

    private val _isLogcatWindowOpen = MutableStateFlow(false)
    val isLogcatWindowOpen = _isLogcatWindowOpen.asStateFlow()

    private val _logcatLines = mutableStateListOf<LogLine>()
    val logcatLines: List<LogLine> get() = _logcatLines

    private val _logcatFilter = MutableStateFlow("")
    val logcatFilter = _logcatFilter.asStateFlow()

    private val adbObserver = AdbObserver(this)
    private val fastbootClient = FastbootClient()

    private val RAW_LOGCAT_FILE = File("raw_logcat_output.log")

    init {
        startAdbObservation()
    }

    fun pressHome() = viewModelScope.launch { adbObserver.sendKeyEvent(3) }  // KEYCODE_HOME
    fun pressBack() = viewModelScope.launch { adbObserver.sendKeyEvent(4) }  // KEYCODE_BACK
    fun pressEnter() = viewModelScope.launch { adbObserver.sendKeyEvent(66) } // KEYCODE_ENTER

    private fun startAdbObservation() {
        viewModelScope.launch {
            try {
                adbObserver.observeAdb()
            } catch (e: Exception) {
                log("ADB", "Observer error: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun toggleAdbIsValid(isValid: Boolean) {
        _uiState.update { it.copy(adbIsValid = isValid) }
        log("ADB", "Status: ${if (isValid) "Connected" else "Disconnected"}", if (isValid) LogLevel.PASS else LogLevel.ERROR)
    }

    fun setRunning(isRunning: Boolean) {
        _uiState.update { it.copy(isRunning = isRunning) }
    }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = LocalTime.now().toString().take(8)
        viewModelScope.launch {
            _logFlow.emit(LogLine(timestamp, tag, message, level))
        }
    }

    fun captureScreenshot() {
        viewModelScope.launch { adbObserver.captureScreenshot() }
    }

    fun sendText(text: String) {
        viewModelScope.launch { adbObserver.sendText(text) }
    }

    fun clearAppData() {
        viewModelScope.launch { adbObserver.clearAppData("org.example.project") }
    }

    /**
     * Bootイメージの書き込みを一括で行います。
     * @param imageFile 書き込むbootイメージファイル
     */
    fun batchFlashBootImage(imageFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            log("FLASH", "Starting batch flash process for ${imageFile.name}", LogLevel.INFO)

            // 1. adb reboot bootloader
            log("FLASH", "[1/5] Rebooting to bootloader...", LogLevel.INFO)
            adbObserver.rebootToBootloader()
            if (!uiState.value.adbIsValid) {
                log("FLASH", "ADB device not found. Aborting.", LogLevel.ERROR)
                return@launch
            }

            // 2. 10秒待機
            log("FLASH", "[2/5] Waiting for device to enter bootloader mode (10s)...", LogLevel.INFO)
            delay(10000)

            // 3. fastboot devices
            log("FLASH", "[3/5] Detecting device in fastboot mode...", LogLevel.INFO)
            val devices = fastbootClient.getDevices()
            if (devices.isEmpty()) {
                log("FLASH", "No devices found in fastboot mode. Aborting.", LogLevel.ERROR)
                return@launch
            }
            val serial = devices.first()
            log("FLASH", "Device found: $serial", LogLevel.PASS)

            // 4. fastboot flash boot <image>
            log("FLASH", "[4/5] Flashing boot partition...", LogLevel.INFO)
            val flashResult = fastbootClient.flashPartition(serial, "boot", imageFile)
            if (flashResult.exitCode != 0) {
                log("FLASH", "Failed to flash boot partition: ${flashResult.output}", LogLevel.ERROR)
                return@launch
            }
            log("FLASH", "Flash successful: ${flashResult.output}", LogLevel.PASS)

            // 5. fastboot reboot
            log("FLASH", "[5/5] Rebooting device to system...", LogLevel.INFO)
            val rebootResult = fastbootClient.reboot(serial)
            if (rebootResult.exitCode != 0) {
                log("FLASH", "Failed to reboot device: ${rebootResult.output}", LogLevel.ERROR)
                return@launch
            }
            log("FLASH", "Batch flash process completed successfully.", LogLevel.PASS)
        }
    }

    // --- Logcat Monitor Actions ---

    fun openLogcatWindow() {
        _isLogcatWindowOpen.value = true
        startLogcat()
    }

    fun closeLogcatWindow() {
        _isLogcatWindowOpen.value = false
        stopLogcat()
    }

    fun startLogcat() {
        viewModelScope.launch {
            log("Logcat", "Starting logcat stream...", LogLevel.INFO)
            adbObserver.startLogcat() // AdbObserverにLogcat開始を指示
        }
    }

    fun stopLogcat() {
        viewModelScope.launch {
            log("Logcat", "Stopping logcat stream.", LogLevel.INFO)
            adbObserver.stopLogcat()
        }
    }

    fun clearLogcat() {
        _logcatLines.clear()
        log("Logcat", "Logcat display cleared.", LogLevel.INFO)
        viewModelScope.launch {
            adbObserver.clearLogcatBuffer()
        }
    }

    fun updateLogcatFilter(text: String) {
        _logcatFilter.value = text
    }

    /**
     * AdbObserverから1行ずつログを受け取り、パースしてUI用リストに追加します。
     */
    fun onLogcatReceived(rawLine: String) {
        writeRawLogcatToFile(rawLine)
        val parsedLog = parseLogLine(rawLine) ?: LogLine("", "RAW", rawLine, LogLevel.INFO)
        viewModelScope.launch {
            _logcatLines.add(parsedLog)
            if (_logcatLines.size > 2000) _logcatLines.removeAt(0)
        }
    }

    /**
     * ログ行を簡易的にパースします。
     */
    private fun parseLogLine(line: String): LogLine? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null
        
        val timestamp = "${parts[0]} ${parts[1]}"
        val levelStr = parts[4]
        val level = when (levelStr) {
            "E", "F" -> LogLevel.ERROR
            "W" -> LogLevel.WARN
            "D", "V" -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }
        
        val body = line.substringAfter(levelStr).trim()
        val tag = body.substringBefore(":").trim()
        val message = body.substringAfter(":").trim()

        return LogLine(timestamp, tag, message, level)
    }

    /**
     * 生のログをデバッグ用にファイル保存します。
     */
    private fun writeRawLogcatToFile(line: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RAW_LOGCAT_FILE.appendText(line + "\n")
            } catch (e: IOException) {}
        }
    }
}
