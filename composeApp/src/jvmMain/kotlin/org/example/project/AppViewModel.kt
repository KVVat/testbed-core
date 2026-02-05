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
import java.io.IOException

class AppViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState = _uiState.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogLine>(replay = 100)
    val logFlow = _logFlow.asSharedFlow()

    // Logcat Monitor用の状態
    private val _isLogcatWindowOpen = MutableStateFlow(false)
    val isLogcatWindowOpen = _isLogcatWindowOpen.asStateFlow()

    private val _logcatLines = mutableStateListOf<LogLine>() // mutableStateListOfを使用
    val logcatLines: List<LogLine> get() = _logcatLines

    private val _logcatFilter = MutableStateFlow("")
    val logcatFilter = _logcatFilter.asStateFlow()

    // ADB監視クラス (ViewModelが保持し、initで起動)
    private val adbObserver = AdbObserver(this)
    private val fastbootClient = FastbootClient()

    private val RAW_LOGCAT_FILE = File("raw_logcat_output.log")

    init {
        // ViewModel起動と同時にADB監視を開始
        startAdbObservation()
        // デバッグ用にファイルをクリア
        viewModelScope.launch(Dispatchers.IO) {
            if (RAW_LOGCAT_FILE.exists()) {
                RAW_LOGCAT_FILE.delete()
            }
        }
    }

    fun pressHome() = viewModelScope.launch { adbObserver.sendKeyEvent(3) }  // KEYCODE_HOME
    fun pressBack() = viewModelScope.launch { adbObserver.sendKeyEvent(4) }  // KEYCODE_BACK
    fun pressEnter() = viewModelScope.launch { adbObserver.sendKeyEvent(66) } // KEYCODE_ENTER

    private fun startAdbObservation() {
        viewModelScope.launch {
            log("SYSTEM", "Starting ADB Observer...", LogLevel.INFO)
            try {
                adbObserver.observeAdb()
            } catch (e: Exception) {
                log("ADB", "Observer error: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    // --- Public Actions called from UI or Observer ---

    fun toggleAdbIsValid(isValid: Boolean) {
        _uiState.update { it.copy(adbIsValid = isValid) }
        val status = if (isValid) "Connected" else "Disconnected"
        log("ADB", "Status changed: $status", if (isValid) LogLevel.PASS else LogLevel.ERROR)
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
        viewModelScope.launch {
            adbObserver.captureScreenshot()
        }
    }

    /**
     * デバイスにテキストを送信します。
     * @param text 送信する文字列
     */
    fun sendText(text: String) {
        log("UI", "Requesting to send text...")
        viewModelScope.launch {
            adbObserver.sendText(text)
        }
    }

    /**
     * アプリのデータを消去します。
     */
    fun clearAppData() {
        log("UI", "Requesting to clear app data...")
        viewModelScope.launch {
            adbObserver.clearAppData("org.example.project")
        }
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
    }

    fun updateLogcatFilter(text: String) {
        _logcatFilter.value = text
    }

    // AdbObserverからLogcatの各行を受け取るコールバック
    fun onLogcatReceived(rawLine: String) {
        val processedLine = rawLine.trim().replace("\n", " ") // 左右トリムと改行置換
        writeRawLogcatToFile(processedLine) // 整形後の生のログデータをファイルに書き出す
        
        // ここでログ行をパースしてLogLevelやTagを抽出し、LogLineオブジェクトを作成
        val parsedLog = parseLogLine(processedLine)
        _logcatLines.add(parsedLog)

        // 最大行数制限
        if (_logcatLines.size > 2000) { // 例: 2000行
            _logcatLines.removeFirst()
        }
    }

    // Logcatの行をパースするシンプルなヘルパー関数
    private fun parseLogLine(line: String): LogLine {
        // 新しいLogcatの出力フォーマットに合わせた正規表現
        // 例: "[ 02-05 18:53:59.932 1501:27528 W/PackageConfigPersister ] App-specific configuration..."
        val regex = "^\\s*\[\\s*(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+):(\\d+)\\s+([A-Z])/([^\\s\]]+?)\\s*\]\\s*(.*)$".toRegex()
        val matchResult = regex.find(line)

        return if (matchResult != null) {
            val (date, time, pid, tid, levelChar, tag, message) = matchResult.destructured
            val timestamp = "$date $time"
            val level = when (levelChar) {
                "V" -> LogLevel.DEBUG // Verbose
                "D" -> LogLevel.DEBUG
                "I" -> LogLevel.INFO
                "W" -> LogLevel.INFO // WarningをINFOとして扱う
                "E" -> LogLevel.ERROR
                "F" -> LogLevel.ERROR // FatalをERRORとして扱う
                else -> LogLevel.INFO
            }
            LogLine(timestamp, tag.trim(), message.trim(), level)
        } else {
            // パースできない行はINFOとしてそのまま表示
            LogLine(LocalTime.now().toString().take(8), "Logcat", line, LogLevel.INFO)
        }
    }

    // 生のLogcatデータをファイルに書き出す関数
    private fun writeRawLogcatToFile(line: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RAW_LOGCAT_FILE.appendText(line + "\n")
            } catch (e: IOException) {
                log("FileLogger", "Failed to write raw logcat to file: ${e.message}", LogLevel.ERROR)
            }
        }
    }
}