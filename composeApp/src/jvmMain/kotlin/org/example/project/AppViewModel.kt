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
        //val processedLine = rawLine.trim().replace("\n", " ") // 左右トリムと改行置換
        writeRawLogcatToFile(rawLine) // 整形後の生のログデータをファイルに書き出す

        // ここでログ行をパースしてLogLevelやTagを抽出し、LogLineオブジェクトを作成
        val parsedLog = parseLogLine(rawLine.trimEnd()) ?: LogLine("", "RAW", rawLine, LogLevel.INFO)

        viewModelScope.launch {
            // リスト追加処理...
            _logcatLines.add(parsedLog)
            // 最大行数制限
            if (_logcatLines.size > 2000) { // 例: 2000行
                _logcatLines.removeFirst()
            }
        }


    }

    // 正規表現は使いません
    private fun parseLogLine(line: String): LogLine? {
        // 1. 空白文字(\s+)で分割する
        // ログ例: "02-05 20:29:15.996  1501  1501 W CompatConfig: Message..."
        // parts[0]="02-05", parts[1]="20:29:15.996", parts[2]="1501", parts[3]="1501", parts[4]="W"
        val parts = line.trim().split(Regex("\\s+"))

        // ヘッダー要素が足りない行（システムメッセージ等）は弾く
        if (parts.size < 6) return null

        // 2. 確定しているヘッダー情報を取得
        val timestamp = "${parts[0]} ${parts[1]}"
        val pid = parts[2]
        val tid = parts[3]
        val levelStr = parts[4]

        // 3. レベル判定
        val level = when (levelStr) {
            "E", "F" -> LogLevel.ERROR
            "W" -> LogLevel.WARN  // WをWARNにマッピング
            "D", "V" -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }

        // 4. 残りの部分を「タグ」と「メッセージ」に分ける
        // parts[5]以降が本文です。
        // ここだけは「最初のコロン」を探す必要がありますが、ヘッダー除去後なので時刻コロンはもういません。

        // まず、ヘッダーを除いた残りの文字列を再構築します
        // (splitだとスペースが1つに畳まれてしまうため、位置計算で元文字列から切り出すのが安全です)

        // レベル(parts[4])の次にあるスペースの終わりを探す
        // "W " の後ろからが本文の開始位置
        val headerEndIndex = line.indexOf(levelStr) + levelStr.length
        val body = line.substring(headerEndIndex).trimStart()

        // bodyの中の最初のコロンを探す (例: "CompatConfig: Message...")
        val separatorIndex = body.indexOf(':')

        val (tag, message) = if (separatorIndex > 0) {
            // "Tag: Message" のパターン
            val t = body.substring(0, separatorIndex).trim()
            val m = body.substring(separatorIndex + 1).trim()
            t to m
        } else {
            // "Tag Message" (コロンなし) または単語のみのパターン
            // 最初の空白までをタグとするのが一般的
            val firstSpace = body.indexOf(' ')
            if (firstSpace > 0) {
                val t = body.substring(0, firstSpace).trim()
                val m = body.substring(firstSpace + 1).trim()
                t to m
            } else {
                body to "" // メッセージなし
            }
        }

        return LogLine(
            timestamp = timestamp,
            tag = tag,
            message = message,
            level = level
        )
    }

    //private val logcatRegex = Regex("""^(\S+\s\S+)\s+(\d+)\s+(\d+)\s+([A-Z])\s+(.*?):\s?(.*)$""")
    //private val logcatRegex = Regex("""^(\S+\s+\S+)\s+(\d+)\s+(\d+)\s+([A-Z])\s+([^:]*):\s?(.*)$""")

    private val logcatRegex = Regex("""^\s*(\S+\s+\S+)\s+(\d+)\s+(\d+)\s+([A-Z])\s+(.*)$""")
/*
    private fun parseLogLine(line: String): LogLine? {
        val matchResult = logcatRegex.find(line) ?: return null
        val (timestamp, pid, tid, levelStr, content) = matchResult.destructured

        // 【ここがポイント】
        // 正規表現ではなく、文字列操作で「最初のコロン」を探して分割する
        // これなら "Tag: Message" も "Tag Message" も柔軟に処理できます
        val separatorIndex = content.indexOf(':')

        val (tag, message) = if (separatorIndex > 0) {
            // コロンが見つかったら、そこまでをタグとする
            val t = content.substring(0, separatorIndex).trim()
            val m = content.substring(separatorIndex + 1).trim() // コロンの次からメッセージ
            t to m
        } else {
            // コロンがない行（システムログ等）は、全体をタグ扱いにするかメッセージ扱いにする
            // ここではcontent全体をタグ、メッセージを空として扱います（見やすさ優先）
            content.trim() to ""
        }

        val level = when (levelStr) {
            "E", "F" -> LogLevel.ERROR
            "W" -> LogLevel.INFO
            "D", "V" -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }

        return LogLine(
            timestamp = timestamp,
            tag = tag,
            message = message,
            level = level
        )
    }
    */

    // Logcatの行をパースするシンプルなヘルパー関数
    /*private fun parseLogLine(line: String): LogLine? {

        val matchResult = logcatRegex.find(line.trim()) ?: return null
        val (timestamp, pid, tid, levelStr, tag, message) = matchResult.destructured

        val level = when (levelStr) {
            "E", "F" -> LogLevel.ERROR
            "W" -> LogLevel.INFO
            else -> LogLevel.DEBUG
        }
        return LogLine(
            timestamp = timestamp, // 秒以下も含む "02-06 00:22:32.619"
            tag = tag.trim(),
            message = message,
            level = level
        )
    }*/

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