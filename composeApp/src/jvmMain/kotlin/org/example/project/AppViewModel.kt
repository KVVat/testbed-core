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
import java.io.FileOutputStream
import java.nio.file.Paths
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

// 不足しているインポート（実際のパッケージ名に合わせて調整してください）
// import org.example.project.junit.AntXmlRunListener
// import org.example.project.junit.JUnitTestRunner

data class TestPlugin(
    val id: String,
    val name: String,
    val clazz: Class<*>,
    val shortName: String,
    val status: String = "Ready"
)

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

    private val _testPlugins = mutableStateListOf<TestPlugin>()
    val testPlugins: List<TestPlugin> get() = _testPlugins

    private val adbObserver = AdbObserver(this)
    private val fastbootClient = FastbootClient()

    private val RAW_LOGCAT_FILE = File("raw_logcat_output.log")

    init {
        startAdbObservation()
        // jvmTestにあるクラスは実行時にリフレクションで取得を試みる
        try {
            val testClass = Class.forName("org.example.project.ComposeAppDesktopTest")
            _testPlugins.add(TestPlugin("sample_01", "Sample Desktop Test", testClass, "SampleTest", "Ready"))
        } catch (e: Exception) {
            // クラスが見つからない場合はログに出力
            println("Test class not found: ${e.message}")
        }
    }

    fun toggleIsRunning(isRunning: Boolean) {
        _uiState.update { it.copy(isRunning = isRunning) }
    }

    fun logging(message: String) {
        log("JUnit", message, LogLevel.DEBUG)
    }

    private fun output_path(): String {
        val dir = File("build/test-results").apply { mkdirs() }
        return dir.absolutePath
    }

    fun runTest(plugin: TestPlugin) {
        if (uiState.value.isRunning) return

        viewModelScope.launch(Dispatchers.IO) {
            toggleIsRunning(true)
            log("TEST", "Starting: ${plugin.name}", LogLevel.INFO)

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val props = Properties().apply {
                setProperty("SFR.shortname", plugin.shortName)
            }

            try {
                // ここで reflection を使用してクラスが利用可能か再確認
                val clazz = plugin.clazz

                // JUnitの実行 (実際のクラス名に合わせてキャストや呼び出しを行ってください)
                // val antRunner = AntXmlRunListener(::logging, props) {
                //     toggleIsRunning(false)
                // }
                // antRunner.setOutputStream(FileOutputStream(File(output_path(), "junit-report-${plugin.shortName}-$timestamp.xml")))
                // val runner = JUnitTestRunner(arrayOf(clazz), antRunner)
                // runner.start()

                // 仮実装としてのログ出力
                log("TEST", "Running Test Runner for ${clazz.simpleName}...", LogLevel.INFO)
                delay(2000)
                toggleIsRunning(false)
                log("TEST", "Finished.", LogLevel.PASS)

            } catch (e: Exception) {
                log("TEST", "Error: ${e.message}", LogLevel.ERROR)
                toggleIsRunning(false)
            }
        }
    }

    // --- 以下、既存のロジックを完全に維持 ---

    fun pressHome() = viewModelScope.launch { adbObserver.sendKeyEvent(3) }
    fun pressBack() = viewModelScope.launch { adbObserver.sendKeyEvent(4) }
    fun pressEnter() = viewModelScope.launch { adbObserver.sendKeyEvent(66) }

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

    fun openLogcatWindow() {
        _isLogcatWindowOpen.value = true
        startLogcat()
    }

    fun closeLogcatWindow() {
        _isLogcatWindowOpen.value = false
        stopLogcat()
    }

    fun startLogcat() {
        viewModelScope.launch { adbObserver.startLogcat() }
    }

    fun stopLogcat() {
        adbObserver.stopLogcat()
    }

    fun clearLogcat() {
        _logcatLines.clear()
        viewModelScope.launch { adbObserver.clearLogcatBuffer() }
    }

    fun updateLogcatFilter(text: String) {
        _logcatFilter.value = text
    }

    fun onLogcatReceived(rawLine: String) {
        writeRawLogcatToFile(rawLine)
        val parsedLog = parseLogLine(rawLine) ?: LogLine("", "RAW", rawLine, LogLevel.INFO)
        _logcatLines.add(parsedLog)
    }

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

    private fun writeRawLogcatToFile(line: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RAW_LOGCAT_FILE.appendText(line + "\n")
            } catch (e: IOException) {}
        }
    }
}

