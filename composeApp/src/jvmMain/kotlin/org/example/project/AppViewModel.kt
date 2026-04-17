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
    val logcatBufferSize: Int = 2000,
    val logcatPastMinutes: Int = 10,
    val mcpServerHost: String = "0.0.0.0",
    val useMcpFallback: Boolean = true
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

    private val _uiDumpScreenWidth = MutableStateFlow(1080)
    val uiDumpScreenWidth = _uiDumpScreenWidth.asStateFlow()

    private val _uiDumpScreenHeight = MutableStateFlow(2400)
    val uiDumpScreenHeight = _uiDumpScreenHeight.asStateFlow()

    private val _selectedToolWindowTab = MutableStateFlow(0) // 0: Logcat, 1: UI Inspector
    val selectedToolWindowTab = _selectedToolWindowTab.asStateFlow()

    private val _testPlugins = mutableStateListOf<TestPlugin>()
    val testPlugins: List<TestPlugin> get() = _testPlugins

    private val adbObserver = AdbObserver(this)
    private val fastbootClient = FastbootClient()
    private val mcpServer = org.example.project.mcp.McpSseServer(adbObserver, this)

    val mcpTestResults = java.util.concurrent.CopyOnWriteArrayList<org.example.project.mcp.McpTestResult>()
    val mcpTestLogs = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
    var currentTestStep: String = ""
    var currentTestProgress: Int = 0
    private val baseDir: File = run {
        val os = System.getProperty("os.name").lowercase()
        
        // Mac環境で、かつ .app バンドル内から実行されている場合
        if (os.contains("mac")) {
            val codeSource = AppViewModel::class.java.protectionDomain.codeSource
            val location = codeSource?.location
            if (location != null) {
                val path = File(location.toURI()).absolutePath
                if (path.contains(".app")) {
                    val userHome = System.getProperty("user.home")
                    val appSupportDir = File(userHome, "Library/Application Support/TestbedCore")
                    
                    // ディレクトリが存在しない場合は作成
                    if (!appSupportDir.exists()) {
                        appSupportDir.mkdirs()
                    }
                    return@run appSupportDir
                }
            }
        }
        
        // Windows パッケージ版: File(".") が System32 を指す問題の回避
        if (os.contains("win")) {
            // 1. compose.application.resources.dir から app root を推定
            //    パッケージ版では <app_root>/app/resources を指す
            val resourcesDirProp = System.getProperty("compose.application.resources.dir")
            if (resourcesDirProp != null) {
                val resFile = File(resourcesDirProp)
                val appRoot = resFile.parentFile?.parentFile
                if (appRoot != null && appRoot.exists()) {
                    println("[BOOT] Windows: baseDir resolved from resources.dir -> ${appRoot.absolutePath}")
                    return@run appRoot
                }
            }
            
            // 2. protectionDomain (jarファイルの場所) から推定
            try {
                val codeSource = AppViewModel::class.java.protectionDomain?.codeSource
                if (codeSource != null) {
                    val jarDir = File(codeSource.location.toURI()).parentFile
                    if (jarDir != null && jarDir.exists()) {
                        val appRoot = jarDir.parentFile ?: jarDir
                        println("[BOOT] Windows: baseDir resolved from codeSource -> ${appRoot.absolutePath}")
                        return@run appRoot
                    }
                }
            } catch (e: Exception) {
                println("[BOOT] Windows: codeSource resolution failed: ${e.message}")
            }
            
            // 3. System32 でないことを確認した上で user.dir をフォールバック
            val userDir = File(System.getProperty("user.dir"))
            if (!userDir.absolutePath.lowercase().contains("system32")) {
                println("[BOOT] Windows: baseDir fallback to user.dir -> ${userDir.absolutePath}")
                return@run userDir
            }
            
            println("[BOOT] WARNING: All Windows baseDir strategies failed, falling back to File(\".\")")
        }
        
        // デフォルト（Linux, 開発時）はカレントディレクトリ
        File(".").absoluteFile
    }

    private val logRecorder = LogRecorder(baseFileName = File(baseDir, "logcat.log").absolutePath)
    private val mainLogRecorder = LogRecorder(baseFileName = File(baseDir, "main.log").absolutePath)

    private val PLUGINS_DIR = File(baseDir, "plugins")

    private val SETTINGS_FILE = File(baseDir, "app_settings.properties")
    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings = _appSettings.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    fun showSnackbar(message: String) {
        viewModelScope.launch {
            _snackbarMessage.emit(message)
        }
    }

    private fun extractDefaultAgentIfNeeded() {
        val resourcesDir = File(baseDir, "resources")
        if (!resourcesDir.exists()) {
            resourcesDir.mkdirs()
        }
        val agentFile = File(resourcesDir, "mutton-agent.apk")
        
        val resourceUrl = AppViewModel::class.java.classLoader.getResource("mutton-agent.apk")
        if (resourceUrl != null) {
            val resourceConnection = resourceUrl.openConnection()
            val resourceLastModified = resourceConnection.lastModified
            
            val shouldCopy = if (!agentFile.exists()) {
                true
            } else {
                // リソースの更新日時がファイルの更新日時より新しい場合は上書き
                val fileLastModified = agentFile.lastModified()
                resourceLastModified > fileLastModified
            }
            
            if (shouldCopy) {
                log("APP", "Extracting default agent APK to ${agentFile.absolutePath}", LogLevel.INFO)
                resourceUrl.openStream().use { input ->
                    agentFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // ファイルの更新日時をリソースと合わせる
                agentFile.setLastModified(resourceLastModified)
            }
        } else {
            log("APP", "Default agent APK not found in resources", LogLevel.WARN)
        }
    }

    init {
        // デバッグ: 起動環境情報 (Windows等でのパス問題診断用)
        println("[BOOT] os.name=${System.getProperty("os.name")}")
        println("[BOOT] user.dir=${System.getProperty("user.dir")}")
        println("[BOOT] compose.application.resources.dir=${System.getProperty("compose.application.resources.dir")}")
        println("[BOOT] Resolved baseDir=${baseDir.absolutePath} (exists=${baseDir.exists()})")
        
        extractDefaultAgentIfNeeded()
        loadSettings()
        startAdbObservation()
        mcpServer.start(host = _appSettings.value.mcpServerHost)
        JUnitBridge.logging = { message, level ->
            val internalLevel = when (level) {
                TestLogLevel.DEBUG -> LogLevel.DEBUG
                TestLogLevel.INFO -> LogLevel.INFO
                TestLogLevel.PASS -> LogLevel.PASS
                TestLogLevel.WARN -> LogLevel.WARN
                TestLogLevel.ERROR -> LogLevel.ERROR
            }
            // Also print to System.out so it can be captured by AntXmlListener
            System.out.println("[$level] $message")
            // The tag is fixed to PLUGIN, or obtained dynamically
            log("PLUGIN", message, internalLevel)
        }
        JUnitBridge.onProgress = { step, percent ->
            currentTestStep = step
            currentTestProgress = percent
        }

        JUnitBridge.resourceDir = File(baseDir, "resources").absolutePath
        JUnitBridge.configFilePath = File(baseDir, "config/settings.json").absolutePath
        JUnitBridge.resultsDir = File(baseDir, "results").absolutePath
        JUnitBridge.baseDir = baseDir.absolutePath

        // Load JARs from the plugin directory on startup
        loadPluginsFromDir()
    }

    private fun loadSettings() {
        if (SETTINGS_FILE.exists()) {
            try {
                val props = Properties().apply { load(SETTINGS_FILE.inputStream()) }
                _appSettings.value = AppSettings(
                    autoOpenLogcat = props.getProperty("autoOpenLogcat", "true").toBoolean(),
                    logcatBufferSize = props.getProperty("logcatBufferSize", "30000").toIntOrNull() ?: 30000,
                    logcatPastMinutes = props.getProperty("logcatPastMinutes", "10").toIntOrNull() ?: 10,
                    mcpServerHost = props.getProperty("mcpServerHost", "0.0.0.0"),
                    useMcpFallback = props.getProperty("useMcpFallback", "true").toBoolean()
                )
            } catch (e: Exception) {
                log("SYSTEM", "Failed to load settings: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        val oldHost = _appSettings.value.mcpServerHost
        _appSettings.value = newSettings
        try {
            val props = Properties().apply {
                setProperty("autoOpenLogcat", newSettings.autoOpenLogcat.toString())
                setProperty("logcatBufferSize", newSettings.logcatBufferSize.toString())
                setProperty("logcatPastMinutes", newSettings.logcatPastMinutes.toString())
                setProperty("mcpServerHost", newSettings.mcpServerHost)
                setProperty("useMcpFallback", newSettings.useMcpFallback.toString())
            }
            props.store(SETTINGS_FILE.outputStream(), "App Settings")
            log("SYSTEM", "Settings saved.", LogLevel.INFO)
            
            if (oldHost != newSettings.mcpServerHost) {
                log("SYSTEM", "MCP server host changed. Restarting server...", LogLevel.INFO)
                mcpServer.stop()
                mcpServer.start(host = newSettings.mcpServerHost)
            }
        } catch (e: Exception) {
            log("SYSTEM", "Failed to save settings: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * Opens the results directory in the OS file manager.
     */
    fun openResultsDirectory() {
        val resultsDir = File(baseDir, "results")
        if (!resultsDir.exists()) {
            resultsDir.mkdirs()
        }
        try {
            java.awt.Desktop.getDesktop().open(resultsDir)
        } catch (e: Exception) {
            log("SYSTEM", "Failed to open results directory: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * Imports a plugin from a ZIP file, extracting resources and plugins folders.
     */
    fun importPluginZip(zipFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("SYSTEM", "Importing plugin from ${zipFile.name}...", LogLevel.INFO)
                java.util.zip.ZipFile(zipFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryName = entry.name
                        
                        // 対象のパスを決定
                        val targetFile = when {
                            entryName.startsWith("resources/") -> File(baseDir, entryName)
                            entryName.startsWith("plugin/") -> {
                                // "plugin/" を "plugins/" にマッピング
                                val relativePath = entryName.substringAfter("plugin/")
                                File(PLUGINS_DIR, relativePath)
                            }
                            entryName.startsWith("plugins/") -> File(baseDir, entryName)
                            else -> null // ルートの他のファイルは無視
                        }
                        
                        if (targetFile != null) {
                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else {
                                targetFile.parentFile.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    targetFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                }
                log("SYSTEM", "Plugin imported successfully!", LogLevel.INFO)
                showSnackbar("Plugin imported successfully!")
                
                // プラグイン一覧を更新
                refreshPlugins()
            } catch (e: Exception) {
                log("SYSTEM", "Failed to import plugin: ${e.message}", LogLevel.ERROR)
            }
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
            log("SYSTEM", "Scanning for plugins...", LogLevel.INFO)

            val jarFiles = mutableListOf<File>()
            val rawJarFiles = mutableListOf<File>()
            
            // 1. 基準ディレクトリのpluginsを検索 (通常は ./plugins)
            if (PLUGINS_DIR.exists()) {
                rawJarFiles.addAll(PLUGINS_DIR.walk().filter { it.isFile && it.extension == "jar" })
            }
            
            // 同名のファイルを排除（浅い階層を優先するため、パスの長さでソート）
            rawJarFiles.sortBy { it.absolutePath.length }
            rawJarFiles.forEach { file ->
                if (jarFiles.none { it.name == file.name }) {
                    jarFiles.add(file)
                } else {
                    log("SYSTEM", "Duplicate plugin ignored: ${file.absolutePath}", LogLevel.WARN)
                }
            }
            
            // 2. 開発時用に composeApp/plugins も検索
            val devPluginsDir = File("composeApp/plugins")
            if (devPluginsDir.exists() && devPluginsDir.absolutePath != PLUGINS_DIR.absolutePath) {
                val devJars = devPluginsDir.walk().filter { it.isFile && it.extension == "jar" }.toList()
                // 同名のファイルがない場合のみ追加
                devJars.forEach { file ->
                    if (jarFiles.none { it.name == file.name }) {
                        jarFiles.add(file)
                    } else {
                        log("SYSTEM", "Duplicate dev plugin ignored: ${file.absolutePath}", LogLevel.WARN)
                    }
                }
            }

            if (jarFiles.isEmpty()) {
                log("SYSTEM", "No plugin JARs found. Checked ${PLUGINS_DIR.absolutePath}", LogLevel.INFO)
                return@launch
            } else {
                log("SYSTEM", "Found ${jarFiles.size} plugin JARs", LogLevel.INFO)
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
                                                // アノテーションを読み込むためにクラスをロード
                                                var title = ""
                                                var description = ""
                                                var category = "(none)"

                                                try {
                                                    val loader = java.net.URLClassLoader(arrayOf(jarFile.toURI().toURL()), this.javaClass.classLoader)
                                                    val clazz = loader.loadClass(className)
                                                    val sfrAnnotation = clazz.annotations.find { it.annotationClass.java.simpleName == "SFR" }
                                                    
                                                    if (sfrAnnotation != null) {
                                                        title = try { sfrAnnotation.annotationClass.java.getMethod("title").invoke(sfrAnnotation) as? String } catch(e: Exception) { null } ?: ""
                                                        description = try { sfrAnnotation.annotationClass.java.getMethod("description").invoke(sfrAnnotation) as? String } catch(e: Exception) { null } ?: ""
                                                        category = try { sfrAnnotation.annotationClass.java.getMethod("category").invoke(sfrAnnotation) as? String } catch(e: Exception) { null } ?: "(none)"
                                                    }
                                                } catch (e: Exception) {
                                                    // ロード失敗時はログを出してフォールバック
                                                    println("Failed to read annotations for $className: ${e.message}")
                                                }

                                                // 文字列が存在しない場合のフォールバック
                                                if (title.isBlank()) title = shortName
                                                if (description.isBlank()) description = "No description available."
                                                if (category.isBlank()) category = "(none)"

                                                _testPlugins.add(
                                                    TestPlugin(
                                                        id = "${parentDirName}_$shortName",
                                                        name = "[$parentDirName] $shortName",
                                                        className = className,
                                                        jarFile = jarFile,
                                                        shortName = shortName,
                                                        title = title,
                                                        description = description,
                                                        category = category
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
        val dir = File(baseDir, "results").apply { mkdirs() }
        return dir.absolutePath
    }

    fun runTest(plugin: TestPlugin, methodName: String? = null, isMcp: Boolean = false) {
        if (uiState.value.isRunning) {
            if (isMcp) {
                mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", "Another test is already running", null))
            }
            return
        }

        if (isMcp) {
            mcpTestResults.clear()
            mcpTestLogs.clear()
        }
        currentTestStep = "Starting Test"
        currentTestProgress = 0

        viewModelScope.launch(Dispatchers.IO) {
            val resultsDir = File(output_path())
            val lockFile = File(resultsDir, "${plugin.shortName}.lock")

            // 重複起動チェック
            if (lockFile.exists()) {
                val lastModified = lockFile.lastModified()
                val now = System.currentTimeMillis()
                val diffMinutes = (now - lastModified) / (1000 * 60)
                if (diffMinutes >= 10) {
                    log("TEST", "Stale lock file found for ${plugin.shortName}, deleting.", LogLevel.WARN)
                    lockFile.delete()
                } else {
                    log("TEST", "Test ${plugin.shortName} is already running (lock file exists). Aborting.", LogLevel.WARN)
                    if (isMcp) {
                        mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", "Test is already running (lock file exists)", null))
                    }
                    return@launch
                }
            }

            toggleIsRunning(true)
            log("TEST", ">>> START: ${plugin.name}${if(methodName != null) "#$methodName" else ""}", LogLevel.INFO)

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

            // ロックファイルの作成
            try {
                lockFile.writeText(timestamp)
            } catch (e: Exception) {
                log("TEST", "Failed to create lock file: ${e.message}", LogLevel.ERROR)
                if (isMcp) {
                    mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", "Failed to create lock file: ${e.message}", null))
                }
                toggleIsRunning(false)
                return@launch
            }

            val props = Properties().apply {
                setProperty("SFR.shortname", plugin.shortName)
            }

            var fos: FileOutputStream? = null
            
            // For MCP output capture
            val originalOut = System.out
            val originalErr = System.err
            val outCapture = java.io.ByteArrayOutputStream()
            val errCapture = java.io.ByteArrayOutputStream()
            
            class TeeStream(val main: java.io.OutputStream, val branch: java.io.OutputStream) : java.io.OutputStream() {
                override fun write(b: Int) { main.write(b); branch.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { main.write(b, off, len); branch.write(b, off, len) }
                override fun flush() { main.flush(); branch.flush() }
            }
            
            val outPrintStream = java.io.PrintStream(TeeStream(originalOut, outCapture))
            val errPrintStream = java.io.PrintStream(TeeStream(originalErr, errCapture))

            try {
                val antRunner = AntXmlRunListener(::logging, props) {
                    viewModelScope.launch {
                        toggleIsRunning(false)
                        log("TEST", "<<< FINISH: ${plugin.name}${if(methodName != null) "#$methodName" else ""}", LogLevel.PASS)
                    }
                }

                val reportFile = File(resultsDir, "junit-report-${plugin.shortName}-$timestamp.xml")
                fos = FileOutputStream(reportFile)
                antRunner.setOutputStream(fos)

                val originalClassLoader = Thread.currentThread().contextClassLoader
                val targetClass = plugin.resolveClass()

                Thread.currentThread().contextClassLoader = targetClass.classLoader
                
                if (isMcp) {
                    System.setOut(outPrintStream)
                    System.setErr(errPrintStream)
                }
                
                try {
                    val runner = JUnitTestRunner(arrayOf(targetClass), antRunner)
                    if (methodName != null) {
                        runner.methodNameToRun = methodName
                    }
                    
                    if (isMcp) {
                        runner.addListener(object : org.junit.runner.notification.RunListener() {
                            override fun testFinished(description: org.junit.runner.Description) {
                                if (mcpTestResults.none { it.method_name == description.methodName }) {
                                    mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", description.methodName, "Pass"))
                                }
                            }
                            override fun testFailure(failure: org.junit.runner.notification.Failure) {
                                val assertionMsg = failure.message
                                val stacktrace = failure.trace
                                mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", failure.description.methodName, "Fail", assertionMsg, stacktrace))
                            }
                        })
                    }
                    
                    runner.addListener(UnitTestingTextListener(::logging){})
                    runner.run()
                } finally {
                    if (isMcp) {
                        outPrintStream.flush()
                        errPrintStream.flush()
                        System.setOut(originalOut)
                        System.setErr(originalErr)
                    }
                    Thread.currentThread().contextClassLoader = originalClassLoader
                }
                
                if (isMcp) {
                    antRunner.setSystemOutput(outCapture.toString("UTF-8"))
                    antRunner.setSystemError(errCapture.toString("UTF-8"))
                }

                fos.flush()
            } catch (e: Exception) {
                log("TEST", "ERROR: ${e.message}", LogLevel.ERROR)
                if (isMcp) {
                    mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", e.message, e.stackTraceToString()))
                }
                toggleIsRunning(false)
            } finally {
                try { fos?.close() } catch (e: Exception) {}
                
                // XMLパッチのマージ (遅延書き出しを待機)
                viewModelScope.launch(Dispatchers.IO) {
                    val patchFile = File(resultsDir, "xml-patches/PATCH-junit-report-${plugin.shortName}-$timestamp.xml")
                    val reportFile = File(resultsDir, "junit-report-${plugin.shortName}-$timestamp.xml")
                    
                    // パッチファイルの存在を待機 (最大2秒)
                    var retry = 0
                    while (!patchFile.exists() && retry < 20) {
                        delay(100)
                        retry++
                    }
                    
                    if (patchFile.exists() && reportFile.exists()) {
                        // ファイル書き出しの完了を少し待つ
                        delay(200)
                        org.example.project.tools.XmlMerger.merge(reportFile, patchFile)
                        // パッチ適用後に削除
                        patchFile.delete()
                        // HTMLレポートの生成
                        generateHtmlReport(reportFile)
                    } else {
                        log("TEST", "Merge skipped: files not found (Patch: ${patchFile.exists()}, Report: ${reportFile.exists()})", LogLevel.WARN)
                        // パッチがなくてもレポートがあればHTML生成を試みる
                        if (reportFile.exists()) {
                            generateHtmlReport(reportFile)
                        }
                    }
                    
                    // ロックファイルの削除
                    if (lockFile.exists()) {
                        lockFile.delete()
                    }
                }
            }
        }
    }

    private fun generateHtmlReport(xmlFile: File) {
        try {
            val xsltInputStream = javaClass.classLoader.getResourceAsStream("summary.xslt")
            if (xsltInputStream == null) {
                log("TEST", "summary.xslt not found in resources", LogLevel.ERROR)
                return
            }
            
            val factory = javax.xml.transform.TransformerFactory.newInstance()
            val transformer = factory.newTransformer(javax.xml.transform.stream.StreamSource(xsltInputStream))
            
            val htmlFile = File(xmlFile.parentFile, xmlFile.name.replace(".xml", ".html"))
            
            transformer.transform(
                javax.xml.transform.stream.StreamSource(xmlFile),
                javax.xml.transform.stream.StreamResult(htmlFile)
            )
            log("TEST", "HTML report generated: ${htmlFile.absolutePath}", LogLevel.INFO)
        } catch (e: Exception) {
            log("TEST", "Failed to generate HTML report: ${e.message}", LogLevel.ERROR)
        }
    }

    fun runTestForMcp(className: String, methodName: String?) {
        val plugin = testPlugins.find { it.className == className }
        if (plugin == null) {
            log("SYSTEM", "Test plugin not found: $className", LogLevel.ERROR)
            mcpTestResults.add(org.example.project.mcp.McpTestResult(className, methodName ?: "Unknown", "Error", "Test plugin not found: $className", null))
            return
        }
        runTest(plugin, methodName, isMcp = true)
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
        val logLine = LogLine(timestamp, tag, message, level)
        viewModelScope.launch { _logFlow.emit(logLine) }
        viewModelScope.launch(Dispatchers.IO) {
            mainLogRecorder.write(logLine)
        }
        if (_uiState.value.isRunning) {
            mcpTestLogs.add(mapOf("time" to timestamp, "level" to level.name, "message" to "[$tag] $message"))
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
        mainLogRecorder.close()
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
            val response = adbObserver.dumpMuttonAgent(includeImage = true)
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
