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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.example.project.adb.AdbRepository
import org.example.project.junit.JUnitTestExecutor
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
    val autoOpenLogcat: Boolean = false,
    val logcatBufferSize: Int = 2000,
    val logcatPastMinutes: Int = 10,
    val mcpServerHost: String = "0.0.0.0",
    val useMcpFallback: Boolean = true
)

class MainViewModel : ViewModel(), KoinComponent {
    private val adbRepository: AdbRepository by inject()
    private val testExecutor: JUnitTestExecutor by inject()
    
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState = _uiState.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogLine>(replay = 100)
    val logFlow = _logFlow.asSharedFlow()



    private val _testPlugins = mutableStateListOf<TestPlugin>()
    val testPlugins: List<TestPlugin> get() = _testPlugins

    private val fastbootClient = FastbootClient()
    private val mcpServer = org.example.project.mcp.McpSseServer(adbRepository.adbObserver, this)

    val mcpTestResults = java.util.concurrent.CopyOnWriteArrayList<org.example.project.mcp.McpTestResult>()
    val mcpTestLogs = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
    var currentTestStep: String = ""
    var currentTestProgress: Int = 0
    // baseDir初期化中はlog()が使えないため、メッセージを一時的に溜め込む
    private val _bootMessages = mutableListOf<Pair<String, LogLevel>>()

    private val baseDir: File = run {
        val os = System.getProperty("os.name").lowercase()
        
        // Mac環境で、かつ .app バンドル内から実行されている場合
        if (os.contains("mac")) {
            val codeSource = MainViewModel::class.java.protectionDomain.codeSource
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
                    _bootMessages.add("Windows: baseDir resolved from resources.dir -> ${appRoot.absolutePath}" to LogLevel.INFO)
                    return@run appRoot
                }
            }
            
            // 2. protectionDomain (jarファイルの場所) から推定
            try {
                val codeSource = MainViewModel::class.java.protectionDomain?.codeSource
                if (codeSource != null) {
                    val jarDir = File(codeSource.location.toURI()).parentFile
                    if (jarDir != null && jarDir.exists()) {
                        val appRoot = jarDir.parentFile ?: jarDir
                        _bootMessages.add("Windows: baseDir resolved from codeSource -> ${appRoot.absolutePath}" to LogLevel.INFO)
                        return@run appRoot
                    }
                }
            } catch (e: Exception) {
                _bootMessages.add("Windows: codeSource resolution failed: ${e.message}" to LogLevel.WARN)
            }
            
            // 3. System32 でないことを確認した上で user.dir をフォールバック
            val userDir = File(System.getProperty("user.dir"))
            if (!userDir.absolutePath.lowercase().contains("system32")) {
                _bootMessages.add("Windows: baseDir fallback to user.dir -> ${userDir.absolutePath}" to LogLevel.WARN)
                return@run userDir
            }
            
            _bootMessages.add("WARNING: All Windows baseDir strategies failed, falling back to File(\".\")" to LogLevel.ERROR)
        }
        
        // デフォルト（Linux, 開発時）はカレントディレクトリ
        File(".").absoluteFile
    }

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
        
        val resourceUrl = MainViewModel::class.java.classLoader.getResource("mutton-agent.apk")
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
        log("BOOT", "os.name=${System.getProperty("os.name")}", LogLevel.INFO)
        log("BOOT", "user.dir=${System.getProperty("user.dir")}", LogLevel.INFO)
        log("BOOT", "compose.application.resources.dir=${System.getProperty("compose.application.resources.dir")}", LogLevel.INFO)
        log("BOOT", "Resolved baseDir=${baseDir.absolutePath} (exists=${baseDir.exists()})", LogLevel.INFO)
        // baseDir初期化中に溜め込んだメッセージをここでログに出力
        _bootMessages.forEach { (msg, level) -> log("BOOT", msg, level) }
        _bootMessages.clear()
        
        JUnitBridge.baseDir = baseDir.absolutePath
        JUnitBridge.resourceDir = File(baseDir, "resources").absolutePath
        JUnitBridge.resultsDir = File(baseDir, "results").absolutePath

        extractDefaultAgentIfNeeded()
        loadSettings()
        
        // Collect flows from AdbRepository
        viewModelScope.launch {
            adbRepository.adbState.collect { state ->
                updateAdbState(state.isValid, state.isUnauthorized, state.deviceSerial, state.deviceInfo)
            }
        }
        viewModelScope.launch {
            adbRepository.logs.collect { event ->
                log(event.tag, event.message, event.level)
            }
        }
        viewModelScope.launch {
            testExecutor.logs.collect { event ->
                log(event.tag, event.message, event.level)
            }
        }
        viewModelScope.launch {
            testExecutor.isRunning.collect { isRunning ->
                toggleIsRunning(isRunning)
            }
        }
        viewModelScope.launch {
            testExecutor.currentTestStep.collect { step ->
                currentTestStep = step
            }
        }
        viewModelScope.launch {
            testExecutor.currentTestProgress.collect { progress ->
                currentTestProgress = progress
            }
        }


        mcpServer.start(host = _appSettings.value.mcpServerHost)


        // Load JARs from the plugin directory on startup
        loadPluginsFromDir()
    }

    private fun loadSettings() {
        if (SETTINGS_FILE.exists()) {
            try {
                val props = Properties().apply { load(SETTINGS_FILE.inputStream()) }
                _appSettings.value = AppSettings(
                    autoOpenLogcat = props.getProperty("autoOpenLogcat", "false").toBoolean(),
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
                                                var methods = emptyList<String>()

                                                try {
                                                    val loader = java.net.URLClassLoader(arrayOf(jarFile.toURI().toURL()), this.javaClass.classLoader)
                                                    val clazz = loader.loadClass(className)
                                                    val sfrAnnotation = clazz.annotations.find { it.annotationClass.java.simpleName == "SFR" }
                                                    
                                                    if (sfrAnnotation != null) {
                                                        title = try { sfrAnnotation.annotationClass.java.getMethod("title").invoke(sfrAnnotation) as? String } catch(e: Exception) { null } ?: ""
                                                        description = try { sfrAnnotation.annotationClass.java.getMethod("description").invoke(sfrAnnotation) as? String } catch(e: Exception) { null } ?: ""
                                                        category = try { sfrAnnotation.annotationClass.java.getMethod("category").invoke(sfrAnnotation) as? String } catch(e: Exception) { null } ?: "(none)"
                                                    }
                                                    
                                                    methods = clazz.methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.map { it.name }
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
                                                        category = category,
                                                        methods = methods
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
                                        val methods = clazz.methods.filter {
                                            it.isAnnotationPresent(org.junit.Test::class.java)
                                        }.map { it.name }

                                        if (methods.isNotEmpty()) {
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
                                                            shortName = clazz.simpleName,
                                                            methods = methods
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

    fun runTest(plugin: TestPlugin, methodName: String? = null, isMcp: Boolean = false) {
        testExecutor.runTest(plugin, methodName, isMcp)
    }

    fun runTestForMcp(className: String, methodName: String?) {
        val plugin = testPlugins.find { it.className == className }
        if (plugin == null) {
            log("SYSTEM", "Test plugin not found: $className", LogLevel.ERROR)
            testExecutor.mcpTestResults.add(org.example.project.mcp.McpTestResult(className, methodName ?: "Unknown", "Error", "Test plugin not found: $className", null))
            return
        }
        runTest(plugin, methodName, isMcp = true)
    }

    // --- Existing ADB/UI logic (keep as is) ---

    fun pressHome() = viewModelScope.launch { adbRepository.adbObserver.sendKeyEvent(3) }
    fun pressBack() = viewModelScope.launch { adbRepository.adbObserver.sendKeyEvent(4) }
    fun pressEnter() = viewModelScope.launch { adbRepository.adbObserver.sendKeyEvent(66) }



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
        viewModelScope.launch { adbRepository.adbObserver.captureScreenshot() }
    }

    fun sendText(text: String) {
        viewModelScope.launch { adbRepository.adbObserver.sendText(text) }
    }


    fun clearAppData() {
        viewModelScope.launch { adbRepository.adbObserver.clearAppData("org.example.project") }
    }


    //Call when app closing
    override fun onCleared() {
        super.onCleared()
        mainLogRecorder.close()
        mcpServer.stop()
    }

    fun setupMuttonAgent() {
        viewModelScope.launch {
            adbRepository.adbObserver.setupMuttonAgent(forceInstall = false)
        }
    }

    fun reinstallMuttonAgent() {
        viewModelScope.launch {
            adbRepository.adbObserver.setupMuttonAgent(forceInstall = true)
        }
    }



}
