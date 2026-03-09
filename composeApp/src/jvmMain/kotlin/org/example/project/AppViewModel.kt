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
import org.example.project.tools.LogRecorder
import org.example.project.tools.LogcatParser
import org.example.project.tools.ProcessNameResolver
import org.example.project.model.UiNode
import org.example.project.model.DumpResult
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.android.certifications.junit.UnitTestingTextListener
import org.jetbrains.skia.Image
import java.util.Base64


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

    private val _uiDumpRoot = MutableStateFlow<UiNode?>(null)
    val uiDumpRoot = _uiDumpRoot.asStateFlow()

    private val _uiDumpScreenshot = MutableStateFlow<ImageBitmap?>(null)
    val uiDumpScreenshot = _uiDumpScreenshot.asStateFlow()

    private val _selectedToolWindowTab = MutableStateFlow(0) // 0: Logcat, 1: UI Inspector
    val selectedToolWindowTab = _selectedToolWindowTab.asStateFlow()

    private val _testPlugins = mutableStateListOf<TestPlugin>()
    val testPlugins: List<TestPlugin> get() = _testPlugins

    private val adbObserver = AdbObserver(this)
    private val fastbootClient = FastbootClient()
    private val mcpServer = org.example.project.mcp.McpSseServer(adbObserver)

    private val logRecorder = LogRecorder(baseFileName = "logcat.log")
    private val PLUGINS_DIR = File("plugins")

    private val SETTINGS_FILE = File("app_settings.properties")
    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings = _appSettings.asStateFlow()

    init {
        loadSettings()
        startAdbObservation()
        mcpServer.start()
        JUnitBridge.logging = { message, level ->
            val internalLevel = when (level) {
                TestLogLevel.DEBUG -> LogLevel.DEBUG
                TestLogLevel.INFO -> LogLevel.INFO
                TestLogLevel.PASS -> LogLevel.PASS
                TestLogLevel.WARN -> LogLevel.WARN
                TestLogLevel.ERROR -> LogLevel.ERROR
            }
            // The tag is fixed to PLUGIN, or obtained dynamically
            log("PLUGIN", message, internalLevel)
        }

        val currentDir = File(".").absolutePath
        JUnitBridge.resourceDir = File(currentDir, "resources").absolutePath
        JUnitBridge.configFilePath = File(currentDir, "config/settings.json").absolutePath

        // Load JARs from the plugin directory on startup
        loadPluginsFromDir()
    }

    private fun loadSettings() {
        if (SETTINGS_FILE.exists()) {
            try {
                val props = Properties().apply { load(SETTINGS_FILE.inputStream()) }
                _appSettings.value = AppSettings(
                    autoOpenLogcat = props.getProperty("autoOpenLogcat", "true").toBoolean(),
                    logcatBufferSize = props.getProperty("logcatBufferSize", "30000").toIntOrNull()
                        ?: 30000
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
     * Scans JAR files in the plugins directory and loads test classes.
     */
    fun loadPluginsFromDir() {
        // Check for existence of plugins directory and create it
        if (!PLUGINS_DIR.exists()) {
            PLUGINS_DIR.mkdirs()
            log("SYSTEM", "Plugins directory created: ${PLUGINS_DIR.absolutePath}", LogLevel.INFO)
        }

        viewModelScope.launch(Dispatchers.IO) {
            log("SYSTEM", "Scanning for plugins in subdirectories...", LogLevel.INFO)

            // Use walk() to recursively search for JARs in subdirectories
            val jarFiles = PLUGINS_DIR.walk()
                .filter { it.isFile && it.extension == "jar" }
                .toList()

            if (jarFiles.isEmpty()) {
                log("SYSTEM", "No plugin JARs found in ${PLUGINS_DIR.name}", LogLevel.INFO)
                return@launch
            }
            var loadedCount = 0 // ★追加: ロードできた件数をカウント
            jarFiles.forEach { jarFile ->
                try {
                    var isFastLoaded = false;

                    java.util.jar.JarFile(jarFile).use { jar ->
                        val entry = jar.getJarEntry("META-INF/testbed-tests.list")
                        if (entry != null) {
                            jar.getInputStream(entry).bufferedReader().useLines { lines ->
                                lines.forEach { className ->
                                    if (className.isNotBlank()) {
                                        withContext(Dispatchers.Main) {
                                            val parentDirName = jarFile.parentFile.name
                                            val shortName = className.substringAfterLast('.')

                                            if (_testPlugins.none { it.className == className && it.jarFile == jarFile }) {
                                                _testPlugins.add(
                                                    TestPlugin(
                                                        id = "${parentDirName}_$shortName",
                                                        name = "[$parentDirName] $shortName",
                                                        className = className, // ★文字列だけ渡す
                                                        jarFile = jarFile,     // ★JARパスを渡す
                                                        shortName = shortName
                                                    )
                                                )
                                                loadedCount++
                                            }
                                        }
                                    }
                                }
                            }
                            isFastLoaded = true //
                        }
                    }


                    // Create a URLClassLoader for each JAR
                    if(!isFastLoaded){
                        val loader =
                            URLClassLoader(arrayOf(jarFile.toURI().toURL()), this.javaClass.classLoader)

                        JarFile(jarFile).use { jar ->
                            val entries = jar.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()

                                // Is a class file and excludes anonymous/inner classes ($)
                                if (entry.name.endsWith(".class") && !entry.name.contains("$")) {
                                    val className = entry.name.replace("/", ".").removeSuffix(".class")

                                    try {
                                        val clazz = loader.loadClass(className)

                                        // Check for methods annotated with @Test
                                        val hasTestAnnotation = clazz.methods.any {
                                            it.isAnnotationPresent(org.junit.Test::class.java)
                                        }

                                        if (hasTestAnnotation) {
                                            withContext(Dispatchers.Main) {
                                                // Prevent duplicate registration
                                                if (_testPlugins.none { it.clazz == clazz }) {
                                                    // Get the folder name for easy identification
                                                    val parentDirName = jarFile.parentFile.name
                                                    _testPlugins.add(
                                                        TestPlugin(
                                                            id = "${parentDirName}_${clazz.simpleName}",
                                                            name = "${clazz.simpleName}",
                                                            clazz = clazz,
                                                            shortName = clazz.simpleName
                                                        )
                                                    )
                                                    loadedCount++
                                                }
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        // Skip class loading failures
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log(
                        "SYSTEM",
                        "Failed to load JAR [${jarFile.name}]: ${e.message}",
                        LogLevel.ERROR
                    )
                }
            }
            // ★追加: 最後にまとめて結果を出力
            if (loadedCount > 0) {
                log("SYSTEM", "$loadedCount plugins loaded.", LogLevel.PASS)
            } else {
                log("SYSTEM", "No new plugins loaded.", LogLevel.INFO)
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
        val level = when {
            message.contains("failed") || message.contains("Exception") -> LogLevel.ERROR
            message.contains("[SKIPPED REASON]") || message.contains("skipped") -> LogLevel.WARN
            message.contains("passed") -> LogLevel.PASS
            else -> LogLevel.DEBUG
        }
        log("JUnit", message, level)
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

            val timestamp =
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
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

                val reportFile =
                    File(output_path(), "junit-report-${plugin.shortName}-$timestamp.xml")
                fos = FileOutputStream(reportFile)
                antRunner.setOutputStream(fos)

                // Set the class loader to the current thread's context (for resolving resources in the JAR)
                val originalClassLoader = Thread.currentThread().contextClassLoader
                val targetClass = plugin.resolveClass()

                Thread.currentThread().contextClassLoader = targetClass.classLoader
                //Thread.currentThread().contextClassLoader = plugin.clazz?.classLoader

                try {
                    val runner = JUnitTestRunner(arrayOf(targetClass), antRunner)

                    runner.addListener(UnitTestingTextListener(::logging){})

                    runner.start()
                } finally {
                    Thread.currentThread().contextClassLoader = originalClassLoader
                }

                fos.flush()
            } catch (e: Exception) {
                log("TEST", "ERROR: ${e.message}", LogLevel.ERROR)
                toggleIsRunning(false)
            } finally {
                try {
                    fos?.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    // --- Existing ADB/UI logic (keep as is) ---

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

    fun updateAdbState(isValid: Boolean, isUnauthorized: Boolean = false, serial: String = "", info: String = "") {
        // 状態が変わっていないなら何もしない
        if (_uiState.value.adbIsValid == isValid &&
            _uiState.value.isUnauthorized == isUnauthorized &&
            _uiState.value.deviceSerial == serial) return

        _uiState.update {
            it.copy(
                adbIsValid = isValid,
                isUnauthorized = isUnauthorized,
                deviceSerial = serial,
                deviceInfo = info
            )
        }

        if (isUnauthorized) {
            log("ADB", "Device Unauthorized! Please accept the RSA prompt on the device screen.", LogLevel.WARN)
        } else {
            log("ADB", "Status: ${if (isValid) "Connected ($serial)" else "Disconnected"}", if (isValid) LogLevel.PASS else LogLevel.ERROR)
        }

        if (isValid && _isLogcatWindowOpen.value) {
            startLogcat()
        } else if (!isValid) {
            stopLogcat()
        }
    }
    fun openLogcatWindow() {
        _isLogcatWindowOpen.value = true
        // Just change the state, don't call startLogcat() here immediately (or call it safely)
        startLogcat()
    }

    fun closeLogcatWindow() {
        _isLogcatWindowOpen.value = false
    }

    fun setToolWindowTab(index: Int) {
        _selectedToolWindowTab.value = index
    }

    fun startLogcat() {
        // Added: Guard against running ADB command when device is not connected
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

    fun captureScreenshot() {
        viewModelScope.launch { adbObserver.captureScreenshot() }
    }

    fun sendText(text: String) {
        viewModelScope.launch { adbObserver.sendText(text) }
    }

    fun startScreenshotStream() {
        viewModelScope.launch { adbObserver.startScreenshotStream() }
    }

    fun stopScreenshotStream() {
        viewModelScope.launch { adbObserver.stopScreenshotStream() }
    }

    fun clearAppData() {
        viewModelScope.launch { adbObserver.clearAppData("org.example.project") }
    }

    fun clearLogcat() {
        _logcatLines.clear(); viewModelScope.launch { adbObserver.clearLogcatBuffer() }
    }

    fun updateLogcatFilter(text: String) {
        _logcatFilter.value = text
    }

    companion object {
        private const val MAX_LOG_LINES = 2000
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
            // Add only if the message is not blank (to prevent garbage with only headers)
            if (log.message.isNotBlank()) {
                _logcatLines.add(log)
                // Added: Record the fully constructed log (including process name) to a file
                // Since this is I/O processing, consider moving it to another coroutine/thread if it becomes heavy
                // (PrintWriter has an internal buffer, so it's usually fine as is)
                viewModelScope.launch(Dispatchers.IO) {
                    logRecorder.write(log)
                }
                // Buffer limit
                val limit = appSettings.value.logcatBufferSize
                if (_logcatLines.size > limit) {
                    _logcatLines.removeRange(0, _logcatLines.size - limit)
                }
            }
        }
        pendingLogLine = null
    }

    // For forcibly flushing the remainder when the device is disconnected, etc.
    fun flushLogcatBuffer() {
        flushPendingLog()
    }
    //Call when app closing
    override fun onCleared() {
        super.onCleared()
        logRecorder.close()
        mcpServer.stop()
    }

    fun setupMuttonAgent() {
        viewModelScope.launch {
            adbObserver.setupMuttonAgent(forceInstall = false)
        }
    }

    fun reinstallMuttonAgent() {
        viewModelScope.launch {
            adbObserver.setupMuttonAgent(forceInstall = true)
        }
    }

    fun pingMuttonAgent() {
        viewModelScope.launch {
            adbObserver.pingMuttonAgent()
        }
    }

    fun dumpMuttonAgent() {
        viewModelScope.launch {
            val response = adbObserver.dumpMuttonAgent()
            if (response != null && response.isNotEmpty()) {
                try {
                    val gson = Gson()
                    val dumpResult = gson.fromJson(response, DumpResult::class.java)
                    if (dumpResult != null && dumpResult.status == "ok") {
                        val uiNode = gson.fromJson(dumpResult.output, UiNode::class.java)
                        _uiDumpRoot.value = uiNode
                        
                        if (dumpResult.screenshot != null && dumpResult.screenshot.isNotEmpty()) {
                            try {
                                val bytes = Base64.getDecoder().decode(dumpResult.screenshot)
                                val skiaImage = Image.makeFromEncoded(bytes)
                                _uiDumpScreenshot.value = skiaImage.toComposeImageBitmap()
                            } catch (e: Exception) {
                                log("SYSTEM", "Failed to decode screenshot: ${e.message}", LogLevel.ERROR)
                                _uiDumpScreenshot.value = null
                            }
                        } else {
                            _uiDumpScreenshot.value = null
                        }

                        // Auto-open tool window and switch to UI Inspector tab
                        openLogcatWindow()
                        setToolWindowTab(1)
                    }
                } catch (e: Exception) {
                    log("SYSTEM", "Failed to parse Dump JSON: \${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

}
