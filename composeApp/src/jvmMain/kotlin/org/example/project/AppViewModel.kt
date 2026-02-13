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
import java.net.URLClassLoader
import java.util.jar.JarFile
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.example.project.junit.xmlreport.AntXmlRunListener
import org.example.project.junit.JUnitTestRunner
import org.example.project.tools.LogcatParser


data class TestPlugin(
    val id: String,
    val name: String,
    val clazz: Class<*>,
    val shortName: String,
    val status: String = "Ready"
)

data class AppSettings(
    val autoOpenLogcat: Boolean = true,
    val logcatBufferSize: Int = 2000
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
    private val PLUGINS_DIR = File("plugins")

    private val SETTINGS_FILE = File("app_settings.properties")
    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings = _appSettings.asStateFlow()

    init {
        loadSettings()
        startAdbObservation()
        JUnitBridge.logging = { message, level ->
            val internalLevel = when(level) {
                TestLogLevel.DEBUG -> LogLevel.DEBUG
                TestLogLevel.INFO -> LogLevel.INFO
                TestLogLevel.PASS -> LogLevel.PASS
                TestLogLevel.WARN -> LogLevel.WARN
                TestLogLevel.ERROR -> LogLevel.ERROR
            }
            // タグは PLUGIN 固定、または動的に取得
            log("PLUGIN", message, internalLevel)
        }

        val currentDir = File(".").absolutePath
        JUnitBridge.resourceDir = File(currentDir, "resources").absolutePath
        JUnitBridge.configFilePath = File(currentDir, "config/settings.json").absolutePath

        // 起動時にプラグインディレクトリからJARをロード
        loadPluginsFromDir()
    }

    private fun loadSettings() {
        if (SETTINGS_FILE.exists()) {
            try {
                val props = Properties().apply { load(SETTINGS_FILE.inputStream()) }
                _appSettings.value = AppSettings(
                    autoOpenLogcat = props.getProperty("autoOpenLogcat", "true").toBoolean(),
                    logcatBufferSize = props.getProperty("logcatBufferSize", "2000").toIntOrNull() ?: 2000
                )
            } catch (e: Exception) {
                log("SYSTEM", "Failed to load settings: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        _appSettings.value = newSettings
        try {
            val props = Properties().apply {
                setProperty("autoOpenLogcat", newSettings.autoOpenLogcat.toString())
                setProperty("logcatBufferSize", newSettings.logcatBufferSize.toString())
            }
            props.store(SETTINGS_FILE.outputStream(), "App Settings")
            log("SYSTEM", "Settings saved.", LogLevel.INFO)
        } catch (e: Exception) {
            log("SYSTEM", "Failed to save settings: ${e.message}", LogLevel.ERROR)
        }
    }
    /**
     * pluginsディレクトリ内のJARファイルをスキャンし、テストクラスをロードします。
     */
    fun loadPluginsFromDir() {
        // pluginsディレクトリの存在確認と作成
        if (!PLUGINS_DIR.exists()) {
            PLUGINS_DIR.mkdirs()
            log("SYSTEM", "Plugins directory created: ${PLUGINS_DIR.absolutePath}", LogLevel.INFO)
        }

        viewModelScope.launch(Dispatchers.IO) {
            log("SYSTEM", "Scanning for plugins in subdirectories...", LogLevel.INFO)

            // walk() を使用してサブディレクトリ内も再帰的に JAR を探索
            val jarFiles = PLUGINS_DIR.walk()
                .filter { it.isFile && it.extension == "jar" }
                .toList()

            if (jarFiles.isEmpty()) {
                log("SYSTEM", "No plugin JARs found in ${PLUGINS_DIR.name}", LogLevel.INFO)
                return@launch
            }

            jarFiles.forEach { jarFile ->
                try {
                    // JARごとにURLClassLoaderを作成
                    val loader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), this.javaClass.classLoader)

                    JarFile(jarFile).use { jar ->
                        val entries = jar.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()

                            // クラスファイルであり、かつ無名クラス/内部クラス($)を除外
                            if (entry.name.endsWith(".class") && !entry.name.contains("$")) {
                                val className = entry.name.replace("/", ".").removeSuffix(".class")

                                try {
                                    val clazz = loader.loadClass(className)

                                    // @Test アノテーションが付与されたメソッドの有無を確認
                                    val hasTestAnnotation = clazz.methods.any {
                                        it.isAnnotationPresent(org.junit.Test::class.java)
                                    }

                                    if (hasTestAnnotation) {
                                        withContext(Dispatchers.Main) {
                                            // 重複登録の防止
                                            if (_testPlugins.none { it.clazz == clazz }) {
                                                // フォルダ名を取得して識別しやすくする
                                                val parentDirName = jarFile.parentFile.name
                                                _testPlugins.add(TestPlugin(
                                                    id = "${parentDirName}_${clazz.simpleName}",
                                                    name = "[$parentDirName] ${clazz.simpleName}",
                                                    clazz = clazz,
                                                    shortName = clazz.simpleName
                                                ))
                                                log("SYSTEM", "Plugin loaded: ${clazz.simpleName} (from $parentDirName)", LogLevel.PASS)
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    // クラスロード失敗はスキップ
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("SYSTEM", "Failed to load JAR [${jarFile.name}]: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

    /**
     * Clears the current loaded plugins and rescans the directory.
     * This is useful for development to load updated JARs without restarting the app.
     */
    fun refreshPlugins() {
        if (uiState.value.isRunning) {
            log("SYSTEM", "Cannot reload plugins while test is running.", LogLevel.WARN)
            return
        }

        viewModelScope.launch {
            _testPlugins.clear()
            log("SYSTEM", "Plugins list cleared. Rescanning...", LogLevel.INFO)
            loadPluginsFromDir()
            log("SYSTEM", "Plugins reloaded.", LogLevel.PASS)
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
            log("TEST", ">>> START: ${plugin.name}", LogLevel.INFO)

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val props = Properties().apply {
                setProperty("SFR.shortname", plugin.shortName)
            }

            var fos: FileOutputStream? = null
            try {
                val antRunner = AntXmlRunListener(::logging, props) {
                    viewModelScope.launch {
                        toggleIsRunning(false)
                        log("TEST", "<<< FINISH: ${plugin.name}", LogLevel.PASS)
                    }
                }

                val reportFile = File(output_path(), "junit-report-${plugin.shortName}-$timestamp.xml")
                fos = FileOutputStream(reportFile)
                antRunner.setOutputStream(fos)

                // クラスローダーを現在のスレッドのコンテキストに設定（JAR内のリソース解決用）
                val originalClassLoader = Thread.currentThread().contextClassLoader
                Thread.currentThread().contextClassLoader = plugin.clazz.classLoader

                try {
                    val runner = JUnitTestRunner(arrayOf(plugin.clazz), antRunner)
                    runner.start()
                } finally {
                    Thread.currentThread().contextClassLoader = originalClassLoader
                }

                fos.flush()
            } catch (e: Exception) {
                log("TEST", "ERROR: ${e.message}", LogLevel.ERROR)
                toggleIsRunning(false)
            } finally {
                try { fos?.close() } catch (e: Exception) {}
            }
        }
    }

    // --- 既存のADB/UIロジック（完全に維持） ---

    fun pressHome() = viewModelScope.launch { adbObserver.sendKeyEvent(3) }
    fun pressBack() = viewModelScope.launch { adbObserver.sendKeyEvent(4) }
    fun pressEnter() = viewModelScope.launch { adbObserver.sendKeyEvent(66) }

    private fun startAdbObservation() {
        viewModelScope.launch {
            try { adbObserver.observeAdb() } catch (e: Exception) { log("ADB", "Observer error: ${e.message}", LogLevel.ERROR) }
        }
    }


    fun toggleAdbIsValid(isValid: Boolean) {
        _uiState.update { it.copy(adbIsValid = isValid) }
        log("ADB", "Status: ${if (isValid) "Connected" else "Disconnected"}", if (isValid) LogLevel.PASS else LogLevel.ERROR)

        // ★追加: 接続状態が変わったときのLogcatの自動制御
        if (isValid && _isLogcatWindowOpen.value) {
            startLogcat() // 接続されて、ウィンドウが開いていれば開始
        } else if (!isValid) {
            stopLogcat()  // 切断されたら止める
        }
    }

    fun openLogcatWindow() {
        _isLogcatWindowOpen.value = true
        // 状態を変えるだけで、ここですぐにstartLogcat()は呼ばない（または安全に呼ぶ）
        startLogcat()
    }

    fun closeLogcatWindow() {
        _isLogcatWindowOpen.value = false
        stopLogcat()
    }

    fun startLogcat() {
        // ★追加: デバイス未接続の時は ADB コマンドを叩かないようにガードする
        if (!uiState.value.adbIsValid) {
            log("Logcat", "Waiting for device connection to start logcat...", LogLevel.WARN)
            return
        }
        viewModelScope.launch { adbObserver.startLogcat() }
    }

    fun stopLogcat() {
        adbObserver.stopLogcat()
    }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = LocalTime.now().toString().take(8)
        viewModelScope.launch { _logFlow.emit(LogLine(timestamp, tag, message, level)) }
    }

    fun captureScreenshot() { viewModelScope.launch { adbObserver.captureScreenshot() } }
    fun sendText(text: String) { viewModelScope.launch { adbObserver.sendText(text) } }
    fun clearAppData() { viewModelScope.launch { adbObserver.clearAppData("org.example.project") } }
    fun clearLogcat() { _logcatLines.clear(); viewModelScope.launch { adbObserver.clearLogcatBuffer() } }
    fun updateLogcatFilter(text: String) { _logcatFilter.value = text }

    companion object {
        private const val MAX_LOG_LINES = 2000
    }

    fun onLogcatReceived(rawLine: String) {
        writeRawLogcatToFile(rawLine)
        val parsedLog = LogcatParser.parse(rawLine) ?: LogLine("", "RAW", rawLine, LogLevel.INFO)
        _logcatLines.add(parsedLog)
        val limit = appSettings.value.logcatBufferSize
        if (_logcatLines.size > limit) {
            _logcatLines.removeRange(0, _logcatLines.size - MAX_LOG_LINES)
        }
    }
//    private fun parseLogLine(line: String): LogLine? {
//        val parts = line.trim().split(Regex("\\s+"))
//        if (parts.size < 5) return null
//        val body = line.substringAfter(parts[4]).trim()
//        return LogLine("${parts[0]} ${parts[1]}", body.substringBefore(":").trim(), body.substringAfter(":").trim(), LogLevel.INFO)
//    }
    private fun parseLogLine(line: String): LogLine? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null

        // parts[4] がログレベル ("V", "D", "I", "W", "E", "F" など)
        val levelChar = parts[4]
        val parsedLevel = when (levelChar) {
            "D", "V" -> LogLevel.DEBUG
            "W" -> LogLevel.WARN
            "E", "F" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }

        val body = line.substringAfter(parts[4]).trim()
        val tag = body.substringBefore(":").trim()
        val message = body.substringAfter(":").trim()

        return LogLine(
            timestamp = "${parts[0]} ${parts[1]}",
            tag = tag,
            message = message,
            level = parsedLevel // ハードコーディングを修正
        )
    }
    private fun writeRawLogcatToFile(line: String) {
        viewModelScope.launch(Dispatchers.IO) { try { RAW_LOGCAT_FILE.appendText(line + "\n") } catch (e: IOException) {} }
    }
}
