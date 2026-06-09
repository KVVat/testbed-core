package org.example.project.adb

import androidx.lifecycle.viewModelScope
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.request.device.DeviceState
import com.malinskiy.adam.request.device.ListDevicesRequest
import com.malinskiy.adam.request.logcat.ChanneledLogcatRequest
import com.malinskiy.adam.request.logcat.LogcatReadMode
import com.malinskiy.adam.request.misc.RebootMode
import com.malinskiy.adam.request.misc.RebootRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import com.malinskiy.adam.request.sync.v1.PullFileRequest
import com.malinskiy.adam.request.sync.v1.PushFileRequest
import com.malinskiy.adam.request.forwarding.LocalTcpPortSpec
import com.malinskiy.adam.request.forwarding.PortForwardRequest
import com.malinskiy.adam.request.forwarding.RemoteAbstractPortSpec
import com.malinskiy.adam.request.logcat.LogcatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.project.JUnitBridge
import org.example.project.LogLevel
import org.example.project.adb.rules.AdbDeviceRule
import org.example.project.tools.ProcessNameResolver
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope

class AdbObserver(private val scope: CoroutineScope) {
 
    private enum class SuType {
        STANDARD, // su -c "command"
        UID_BASED, // su 0 command
        ROOT_C,    // su root -c "command"
        UNKNOWN
    }
    private var detectedSuType = SuType.UNKNOWN

    private suspend fun executeWithSu(command: String, serial: String): com.malinskiy.adam.request.shell.v1.ShellCommandResult {
        if (detectedSuType != SuType.UNKNOWN) {
            val suCmd = buildSuCommand(detectedSuType, command)
            return adb.adb.execute(ShellCommandRequest(suCmd), serial)
        }
        
        val tryUid = adb.adb.execute(ShellCommandRequest("su 0 id"), serial)
        if (tryUid.exitCode == 0 && tryUid.output.contains("uid=0")) {
            detectedSuType = SuType.UID_BASED
            val suCmd = buildSuCommand(detectedSuType, command)
            return adb.adb.execute(ShellCommandRequest(suCmd), serial)
        }
        
        val tryStd = adb.adb.execute(ShellCommandRequest("su -c id"), serial)
        if (tryStd.exitCode == 0 && tryStd.output.contains("uid=0")) {
            detectedSuType = SuType.STANDARD
            val suCmd = buildSuCommand(detectedSuType, command)
            return adb.adb.execute(ShellCommandRequest(suCmd), serial)
        }
        
        val tryRootC = adb.adb.execute(ShellCommandRequest("su root -c id"), serial)
        if (tryRootC.exitCode == 0 && tryRootC.output.contains("uid=0")) {
            detectedSuType = SuType.ROOT_C
            val suCmd = buildSuCommand(detectedSuType, command)
            return adb.adb.execute(ShellCommandRequest(suCmd), serial)
        }
        
        detectedSuType = SuType.STANDARD
        val suCmd = buildSuCommand(detectedSuType, command)
        return adb.adb.execute(ShellCommandRequest(suCmd), serial)
    }

    private fun buildSuCommand(type: SuType, command: String): String {
        val escaped = command.replace("\"", "\\\"")
        return when (type) {
            SuType.UID_BASED -> "su 0 sh -c \"$escaped\""
            SuType.STANDARD -> "su -c \"$escaped\""
            SuType.ROOT_C -> "su root sh -c \"$escaped\""
            else -> "su -c \"$escaped\""
        }
    }

    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()
    var isRunning: Boolean = false
    private var logcatJob: Job? = null
    
    // Agent Streams
    private var screenshotStreamJob: Job? = null
    private val _screenshotStream = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val screenshotStream = _screenshotStream.asSharedFlow()

    // New flows for logs and state
    private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()

    private val _adbState = MutableStateFlow(AdbState())
    val adbState = _adbState.asStateFlow()

    private val _logcatLines = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    val logcatLines = _logcatLines.asSharedFlow()

    private val _logcatFlush = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val logcatFlush = _logcatFlush.asSharedFlow()

    private fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        _logs.tryEmit(LogEvent(tag, message, level))
    }

    // Mutton Agentの設定
    private val AGENT_APK_NAME = "mutton-agent.apk"
    private val REMOTE_AGENT_PATH = "/data/local/tmp/$AGENT_APK_NAME"
    // エージェント側で定義するソケット名 (Abstract Unix Domain Socket)
    private val AGENT_SOCKET_NAME = "mutton_agent"
    private val LOCAL_FORWARD_PORT = 11451

    /**
     * adbバイナリの有効なパスを解決します。
     */
    private fun resolveAdbPath(): String? {
        val osName = System.getProperty("os.name").lowercase()
        val adbName = if (osName.contains("win")) "adb.exe" else "adb"
        val home = System.getProperty("user.home")

        // 1. アプリのbaseDir基準でのローカルadb検索
        val appBaseDir = if (JUnitBridge.baseDir.isNotBlank()) File(JUnitBridge.baseDir) else File(".")
        val localAdb = File(appBaseDir, "bin/platform-tools/$adbName")
        if (localAdb.exists() && localAdb.canExecute()) return localAdb.absolutePath

        // 2. OSごとの標準SDKパス
        val sdkPaths = mutableListOf<String>()
        when {
            osName.contains("mac") -> {
                sdkPaths.add("$home/Library/Android/sdk/platform-tools/$adbName")
            }
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) {
                    sdkPaths.add("$localAppData\\Android\\Sdk\\platform-tools\\$adbName")
                }
            }
            osName.contains("linux") -> {
                sdkPaths.add("$home/Android/Sdk/platform-tools/$adbName")
                sdkPaths.add("/usr/lib/android-sdk/platform-tools/$adbName")
            }
        }

        for (path in sdkPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) return file.absolutePath
        }

        // 3. システムパスのチェック
        val inPath = try {
            val checkCmd = if (osName.contains("win")) "where" else "which"
            val process = ProcessBuilder(checkCmd, adbName).start()
            if (process.waitFor() == 0) adbName else null
        } catch (e: Exception) {
            null
        }

        return inPath
    }

    fun checkDependencies(): Boolean {
        val path = resolveAdbPath()
        if (path == null) {
            log("SETUP", "Critical: 'adb' not found. Run the setup script for your OS.", LogLevel.ERROR)
            return false
        }
        log("SETUP", "ADB resolved: $path", LogLevel.PASS)
        return true
    }

    suspend fun captureScreenshot() {
        if (!adbState.value.isValid) {
            log("ADB", "Cannot take screenshot: No device connected.", LogLevel.ERROR)
            return
        }
        withContext(Dispatchers.IO) {
            try {
                log("ADB", "Taking screenshot...", LogLevel.INFO)
                val serial = adb.deviceSerial
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val remotePath = "/sdcard/screenshot_tmp.png"
                val localDir = File("screenshots")
                if (!localDir.exists()) localDir.mkdirs()
                val localFile = File(localDir, "screenshot_$timestamp.png")

                adb.adb.execute(ShellCommandRequest("screencap -p $remotePath"), serial)
                val channel = adb.adb.execute(PullFileRequest(remotePath, localFile), this, serial)
                for (progress in channel) { }

                adb.adb.execute(ShellCommandRequest("rm $remotePath"), serial)
                log("ADB", "Screenshot saved: ${localFile.absolutePath}", LogLevel.PASS)
            } catch (e: Exception) {
                log("ADB", "Screenshot failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun sendText(text: String) {
        if (!adbState.value.isValid) return
        if (text.isBlank()) return

        withContext(Dispatchers.IO) {
            try {
                val escapedText = text.replace(" ", "%s")
                val command = "input text $escapedText"
                val result = adb.adb.execute(ShellCommandRequest(command), adb.deviceSerial)
                if (result.exitCode == 0) {
                    log("ADB", "Text sent: $text", LogLevel.PASS)
                } else {
                    log("ADB", "Input Failed: ${result.output}", LogLevel.ERROR)
                }
            } catch (e: Exception) {
                log("ADB", "Exception sending text: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun clearAppData(packageName: String) {
        if (!adbState.value.isValid) return
        try {
            val output = adb.adb.execute(ShellCommandRequest("pm clear $packageName"), adb.deviceSerial)
            if (output.output.contains("Success")) {
                log("ADB", "Cleared app data for $packageName", LogLevel.INFO)
            }
        } catch (e: Exception) {
            log("ADB", "Clear data failed: ${e.message}", LogLevel.ERROR)
        }
    }

    suspend fun rebootToBootloader() {
        if (!adbState.value.isValid) return
        withContext(Dispatchers.IO) {
            try {
                adb.adb.execute(RebootRequest(RebootMode.BOOTLOADER), adb.deviceSerial)
                log("ADB", "Rebooting to bootloader...", LogLevel.PASS)
            } catch (e: Exception) {
                log("ADB", "Reboot failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun sendKeyEvent(keyCode: Int) {
        if (!adbState.value.isValid) return
        withContext(Dispatchers.IO) {
            try {
                adb.adb.execute(ShellCommandRequest("input keyevent $keyCode"), adb.deviceSerial)
            } catch (e: Exception) {
                log("ADB", "Key event failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    private suspend fun fetchProcessList() {
        if (!adb.isDeviceInitialised()) return
        try {
            val result = adb.adb.execute(ShellCommandRequest("ps -A -o PID,NAME"), adb.deviceSerial)
            val map = mutableMapOf<String, String>()
            result.output.lines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[0].all { it.isDigit() }) {
                    map[parts[0]] = parts[1]
                }
            }
            ProcessNameResolver.updateBulk(map)
        }catch (e: Exception){
            log("ADB", "Failed to fetch process list: ${e.message}", LogLevel.WARN)
        }
    }

    suspend fun startLogcat(pastMinutes: Int = 10) {
        if (!adb.isDeviceInitialised()) return
        ProcessNameResolver.clear()
        fetchProcessList()

        val serial = adb.deviceSerial
        if (!adbState.value.isValid || serial.isBlank()) return
        if (logcatJob?.isActive == true) return

        logcatJob = scope.launch(Dispatchers.IO) {
            val buffer = StringBuilder()
            try {
                //Preapre large buffer for logcat
                //adb.adb.execute(ShellCommandRequest("logcat -G 16M"), adb.deviceSerial)

                val logChannel: ReceiveChannel<String> = adb.adb.execute(
                    request = ChanneledLogcatRequest(
                        since = com.malinskiy.adam.request.logcat.LogcatSinceFormat.TimeStamp(
                            java.time.Instant.now().minusSeconds(pastMinutes * 60L)
                        ),
                        modes = listOf(LogcatReadMode.long,)
                        //,buffers = listOf(LogcatBuffer.all)
                    ),
                    serial = serial,
                    scope = this,

                )
                logChannel.consumeEach { chunk ->
                    buffer.append(chunk)
                    while (buffer.contains("\n")) {
                        val index = buffer.indexOf("\n")
                        val line = buffer.substring(0, index).trimEnd('\r', '\n')
                        if (line.isNotBlank()) _logcatLines.tryEmit(line)
                        buffer.delete(0, index + 1)
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    log("Logcat", "Stream error: ${e.message}", LogLevel.ERROR)
                }
            } finally {
                _logcatFlush.tryEmit(Unit)
                buffer.clear()
            }
        }
    }

    fun stopLogcat() {
        logcatJob?.cancel()
        logcatJob = null
    }

    // ★ Mutton Agentのデプロイと起動
    suspend fun setupMuttonAgent(forceInstall: Boolean = false) {
        if (!adbState.value.isValid) return

        withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                log("Agent", "Initializing Mutton Agent...", LogLevel.INFO)

                // 1. ローカルAPKの特定 (JUnitBridge.resourceDirを使用)
                // composeResources/mutton-agent.apk はビルド後、resources に配置される想定
                val localApk = File(JUnitBridge.resourceDir, "$AGENT_APK_NAME")

                if (!localApk.exists()) {
                    log("Agent", "Agent APK not found at: ${localApk.absolutePath}. Did you build the APK?", LogLevel.ERROR)
                    return@withContext
                }

                // 2. ポートフォワード設定
                // PC: LOCAL_FORWARD_PORT -> Android: abstract socket "mutton_agent"
                log("Agent", "Setting up port forwarding (tcp:$LOCAL_FORWARD_PORT -> localabstract:$AGENT_SOCKET_NAME)...", LogLevel.INFO)
                try {
                    adb.adb.execute(
                        PortForwardRequest(
                            // PC側: TCPポート 11451
                            local = LocalTcpPortSpec(LOCAL_FORWARD_PORT),
                            remote = RemoteAbstractPortSpec(AGENT_SOCKET_NAME),
                            serial = serial
                        )
                    )
                } catch (e: Exception) {
                    log("Agent", "Port forwarding failed: ${e.message}", LogLevel.WARN)
                }

                // 3. すでにエージェントプロセスが動作しているかサイレントにチェック
                // ホストアプリ再起動時に毎回 `am force-stop` を実行すると、Agent(Android Test)側の
                // UiAutomation (Accessibility Service等) の接続状態が壊れ、以降 `rootInActiveWindow` 等が
                // null を返す問題(Bug)が発生するため、ここでプロセスが生存していればキル・再配置をスキップします。
                var isAgentRunning = false
                if (!forceInstall) {
                    // Port forwarding might take a moment to be fully active. Retry a few times.
                    log("Agent", "Pinging agent to check if already running...", LogLevel.INFO)
                    for (i in 1..5) {
                        val pingResponse = sendToAgent("{\"cmd\":\"ping\"}", silent = false, timeoutMs = 500) // Removed silent to get error logs
                        if (pingResponse != null && pingResponse.contains("\"status\":\"pong\"")) {
                            log("Agent", "Agent is already running. Skipping deployment to preserve UiAutomation state.", LogLevel.PASS)
                            isAgentRunning = true
                            break
                        }
                        delay(500) // Wait longer before retrying (total 2.5s)
                    }
                }

                if (!isAgentRunning) {

                // 4. 既存プロセスのクリーンアップ
                try {
                    adb.adb.execute(ShellCommandRequest("am force-stop org.example.mutton.test"), serial)
                } catch (_: Exception) {}

                // ★ 追加： インストール済みかチェックしてスキップ
                val isInstalled = try {
                    val pmCheck = adb.adb.execute(ShellCommandRequest("pm list packages org.example.mutton.test"), serial)
                    pmCheck.output.contains("package:org.example.mutton.test")
                } catch (_: Exception) { false }

                if (isInstalled && !forceInstall) {
                    log("Agent", "Agent APK is already installed. Skipping push and install.", LogLevel.INFO)
                } else {
                    // 3. APKのプッシュ
                    log("Agent", "Pushing agent APK to device...", LogLevel.INFO)
                    val pushChannel = adb.adb.execute(
                        PushFileRequest(localApk, REMOTE_AGENT_PATH),
                        this,
                        serial
                    )
                    // 完了待ち
                    for (progress in pushChannel) {
                        // プログレス表示が必要ならここで
                    }
                    log("Agent", "Agent pushed successfully.", LogLevel.PASS)

                    // 3.5 APKのインストール
                    log("Agent", "Installing agent APK...", LogLevel.INFO)
                    val installResult = adb.adb.execute(ShellCommandRequest("pm install -r -t $REMOTE_AGENT_PATH"), serial)
                    if (installResult.output.contains("Success")) {
                        log("Agent", "Agent installed successfully.", LogLevel.PASS)
                    } else {
                        log("Agent", "Agent installation failed: ${installResult.output}", LogLevel.ERROR)
                    }
                }

                    // 5. エージェントの起動 (am instrument)
                    // バックグラウンドで実行し続けるため、コルーチンで監視する
                    log("Agent", "Starting agent process...", LogLevel.INFO)
                    scope.launch(Dispatchers.IO) {
                        try {
                            // am instrument で androidx.test.runner.AndroidJUnitRunner を起動
                            val cmd = "am instrument -w org.example.mutton.test/androidx.test.runner.AndroidJUnitRunner"
                            adb.adb.execute(ShellCommandRequest(cmd), serial)
                        } catch (e: Exception) {
                            // プロセスが終了した場合やエラー時
                            log("Agent", "Agent process terminated or failed: ${e.message}", LogLevel.WARN)
                        }
                    }

                    log("Agent", "Agent start command issued.", LogLevel.PASS)
                }

                // Wait for the agent to boot up and be ready if we just started it
                if (!isAgentRunning) {
                    log("Agent", "Waiting for agent to become responsive...", LogLevel.INFO)
                    var responsive = false
                    for (i in 1..20) { // Up to 5 seconds
                        val pingResponse = sendToAgent("{\"cmd\":\"ping\"}", silent = true, timeoutMs = 500)
                        if (pingResponse != null && pingResponse.contains("\"status\":\"pong\"")) {
                            responsive = true
                            break
                        }
                        delay(250)
                    }
                    if (responsive) {
                        log("Agent", "Agent is now responsive and ready.", LogLevel.PASS)
                    } else {
                        log("Agent", "Agent failed to respond after starting. Check Logcat for agent crashes.", LogLevel.ERROR)
                    }
                }

                val version = getMuttonAgentVersion()
                if (version != null) {
                    log("Agent", "Active Agent Version: $version", LogLevel.INFO)
                } else {
                    log("Agent", "Failed to retrieve agent version.", LogLevel.WARN)
                }

            } catch (e: Exception) {
                log("Agent", "Setup failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun observeAdb() {
        checkDependencies()

        while (currentCoroutineContext().isActive) {
            var backgroundMonitorJob: Job? = null
            try {
                // 1. 最速検知ループ (そのまま)
                backgroundMonitorJob = scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        if (adb.isUnauthorized) {
                            val earlySerial = adb.getSerialEarly() ?: ""
                            if (!adbState.value.isUnauthorized) {
                                _adbState.value = adbState.value.copy(isValid = false, isUnauthorized = true, deviceSerial = earlySerial)
                            }
                        } else if (adbState.value.isUnauthorized) {
                            // ★追加: 未認可フラグが消えた（許可された or 抜かれた）
                            // 端末が1つも見つからないなら「USBが抜かれた」と判断してDisconnectedに戻す
                            val earlySerial = adb.getSerialEarly()
                            if (earlySerial == null) {
                                //adb.isUnauthorized = false
                                _adbState.value = adbState.value.copy(isValid = false, isUnauthorized = false)
                            }
                        }
                        val earlySerial = adb.getSerialEarly()
                        if (earlySerial != null && logcatJob?.isActive != true) {
                            try { adb.adb.execute(ShellCommandRequest("logcat -G 16M"), earlySerial) } catch (e: Exception) { /* 無視 */ }
                            adb.deviceSerial = earlySerial
                            startLogcat()
                            break
                        }
                        delay(200)
                    }
                }

                // 2. 既存の「完全起動(ロック画面)」を待つ処理
                withContext(Dispatchers.IO) { adb.startAlone() }

                // 完全起動後の処理
                while (currentCoroutineContext().isActive) {
                    delay(1000)
                    if (isRunning) continue

                    if (adb.isDeviceInitialised()) {
                        try {
                            val devices = adb.adb.execute(ListDevicesRequest())
                            val currentDevice = devices.find { it.serial == adb.deviceSerial }

                            // ★ 2. 未認可(Unauthorized)の判定
                            if (currentDevice?.state == DeviceState.UNAUTHORIZED) {
                                if (!adbState.value.isUnauthorized) {
                                    _adbState.value = adbState.value.copy(isValid = false, isUnauthorized = true)
                                }
                                continue // 未認可の場合はここでループをやり直し、echoコマンドを打たない
                            }

                            // 3. 認可済み(Device)なら echo で生存確認
                            adb.adb.execute(ShellCommandRequest("echo"), adb.deviceSerial)

                            // 成功した場合はUIを Authorized (Active) 状態に更新
                            /*if (!adbState.value.isValid || adbState.value.isUnauthorized) {
                                adbProps = AdbProps(adb.osversion, adb.productmodel, adb.deviceSerial, adb.displayId)
                                _adbState.value = adbState.value.copy(isValid = true, isUnauthorized = false)
                            }*/
                            if (!adbState.value.isValid || adbState.value.isUnauthorized) {
                                adbProps = AdbProps(adb.osversion, adb.productmodel, adb.deviceSerial, adb.displayId)

                                // ★ 表示用の文字列を組み立てる
                                val infoStr = """
                                    Serial: ${adbProps.serial}
                                    Model: ${adbProps.model}
                                    OS Version: Android ${adbProps.osVersion}
                                    Display ID: ${adbProps.displayId}
                                """.trimIndent()

                                _adbState.value = adbState.value.copy(
                                    isValid = true,
                                    isUnauthorized = false,
                                    deviceSerial = adbProps.serial,
                                    deviceInfo = infoStr
                                )

                                log("Adb", "Device Auth Success:\n$infoStr", LogLevel.PASS)

                                // ★ Agentの自動セットアップとバージョン確認を出力
                                setupMuttonAgent(forceInstall = false)
                                val v = getMuttonAgentVersion()
                                log("Adb", "Current Agent Version: ${v ?: "Unknown"}", LogLevel.INFO)
                            }
                        } catch (e: Exception) {
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                backgroundMonitorJob?.cancel()
                // 完全な切断時
                if (adbState.value.isValid || adbState.value.isUnauthorized) {
                    _adbState.value = adbState.value.copy(isValid = false, isUnauthorized = false)
                    stopLogcat()
                }
                delay(1000)
            }
        }
    }
    private suspend fun sendToAgent(jsonCmd: String, silent: Boolean = false, timeoutMs: Int = 5000): String? {
        if (!adbState.value.isValid) return null

        return withContext(Dispatchers.IO) {
            try {
                java.net.Socket("127.0.0.1", LOCAL_FORWARD_PORT).use { socket ->
                    socket.soTimeout = timeoutMs // dump can take longer

                    val writer = java.io.PrintWriter(socket.getOutputStream(), true)
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))

                    writer.println(jsonCmd)

                    // The agent returns a single line JSON response per command.
                    // Looping until null will block until socket closure/timeout.
                    val response = reader.readLine()
                    response?.trimEnd()
                }
            } catch (e: Exception) {
                if (!silent) {
                    log("Agent", "Communication failed: ${e.message} (Is agent running?)", LogLevel.ERROR)
                }
                null
            }
        }
    }

    suspend fun startScreenshotStream(fps: Float = 1f, quality: Int = 2) {
        if (!adbState.value.isValid) return
        if (screenshotStreamJob?.isActive == true) return

        log("Agent", "Starting screenshot stream at $fps fps...", LogLevel.INFO)
        val jsonCmd = "{\"cmd\":\"start_stream\",\"fps\":$fps,\"image_quality\":$quality}"
        
        screenshotStreamJob = scope.launch(Dispatchers.IO) {
            try {
                Socket("127.0.0.1", LOCAL_FORWARD_PORT).use { socket ->
                    socket.soTimeout = 0 // Stream is long-lived

                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    writer.println(jsonCmd)

                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            _screenshotStream.tryEmit(line.trimEnd())
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    log("Agent", "Screenshot stream failed: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

    suspend fun stopScreenshotStream() {
        screenshotStreamJob?.cancel()
        screenshotStreamJob = null
        sendToAgent("{\"cmd\":\"stop_stream\"}", silent = true)
        log("Agent", "Screenshot stream stopped.", LogLevel.INFO)
    }

    suspend fun pingMuttonAgent() {
        log("Agent", "Pinging agent at 127.0.0.1:$LOCAL_FORWARD_PORT...", LogLevel.INFO)
        var response = sendToAgent("{\"cmd\":\"ping\"}", silent = true)
        
        if (response == null) {
            log("Agent", "Agent not responding. Attempting auto-setup...", LogLevel.INFO)
            setupMuttonAgent(forceInstall = false)
            response = sendToAgent("{\"cmd\":\"ping\"}")
        }

        if (response != null && response.isNotEmpty()) {
            log("Agent", "Response: $response", LogLevel.PASS)
        } else {
            log("Agent", "No response from agent. Setup might have failed.", LogLevel.WARN)
        }
    }

    suspend fun cleanupMuttonAgent() {
        if (!adbState.value.isValid) return
        withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                log("Agent", "Force-stopping mutton agent...", LogLevel.INFO)
                adb.adb.execute(ShellCommandRequest("am force-stop org.example.mutton.test"), serial)
                adb.adb.execute(ShellCommandRequest("am force-stop org.example.mutton"), serial)
                log("Agent", "Cleanup complete.", LogLevel.PASS)
            } catch (e: Exception) {
                log("Agent", "Cleanup failed: ${e.message}", LogLevel.WARN)
            }
        }
    }

    suspend fun dumpMuttonAgent(includeImage: Boolean = false, quality: Int = 2, silent: Boolean = false): String? {
        val jsonCmd = "{\"cmd\":\"get_ui_dump\",\"include_image\":$includeImage,\"image_quality\":$quality}"
        if (!silent) {
            log("Agent", "Requesting UI dump from agent... (includeImage=$includeImage)", LogLevel.INFO)
        }
        var response = sendToAgent(jsonCmd, silent = true)
        
        // 1. Connection failed
        if (response == null) {
            if (!silent) {
                log("Agent", "Agent not responding. Attempting auto-setup...", LogLevel.INFO)
            }
            setupMuttonAgent(forceInstall = false)
            response = sendToAgent(jsonCmd)
        }
        
        // 2. Dead UIAutomation node (rootInActiveWindow null) -> Requires hard restart
        if (response != null && response.contains("\"status\":\"ng\"") && response.contains("rootInActiveWindow returned null")) {
            log("Agent", "UiAutomation state is broken (rootInActiveWindow null). Need hard restart.", LogLevel.ERROR)
            cleanupMuttonAgent() // Kill the ghost process
            setupMuttonAgent(forceInstall = false) // Restart agent
            response = sendToAgent(jsonCmd) // Retry
        }

        if (response != null && response.isNotEmpty() && !response.contains("\"status\":\"ng\"")) {
            if (!silent) {
                log("Agent", "Dump Success! Output size: ${response.length} chars", LogLevel.PASS)
            }
        } else {
            if (!silent) {
                log("Agent", "Dump failed or empty response.", LogLevel.WARN)
            }
        }
        return response
    }

    suspend fun executeAdbShell(command: String): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            try {
                log("ADB", "Executing shell: $command", LogLevel.INFO)
                val response = adb.adb.execute(ShellCommandRequest(command), adb.deviceSerial)
                if (response.exitCode == 0) {
                    log("ADB", "Shell command succeeded.", LogLevel.PASS)
                    response.output
                } else {
                    log("ADB", "Shell error (exit ${response.exitCode}): ${response.output}", LogLevel.ERROR)
                    "Exit Code ${response.exitCode}:\n${response.output}"
                }
            } catch (e: Exception) {
                log("ADB", "Shell execution failed: ${e.message}", LogLevel.ERROR)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun tapCoordinate(x: Int, y: Int): String? {
        val jsonCmd = "{\"cmd\":\"tap\",\"x\":$x,\"y\":$y}"
        log("Agent", "Requesting tap at ($x, $y)", LogLevel.INFO)
        val response = sendToAgent(jsonCmd, silent = true)
        if (response == null || response.contains("\"status\":\"error\"")) {
            log("Agent", "Tap failed: $response", LogLevel.ERROR)
            return null
        }
        return dumpMuttonAgent(includeImage = false)
    }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int): String? {
        val jsonCmd = "{\"cmd\":\"swipe\",\"start_x\":$startX,\"start_y\":$startY,\"end_x\":$endX,\"end_y\":$endY}"
        log("Agent", "Requesting swipe from ($startX, $startY) to ($endX, $endY)", LogLevel.INFO)
        val response = sendToAgent(jsonCmd, silent = true)
        if (response == null || response.contains("\"status\":\"error\"")) {
            log("Agent", "Swipe failed: $response", LogLevel.ERROR)
            return null
        }
        return dumpMuttonAgent(includeImage = false)
    }

    suspend fun pressKey(keycode: String): String? {
        if (!adbState.value.isValid) return null
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val keycodeArg = if (keycode.startsWith("KEYCODE_")) keycode else "KEYCODE_$keycode"
                log("Agent", "Pressing key via adb: $keycodeArg", LogLevel.INFO)
                adb.adb.execute(ShellCommandRequest("input keyevent $keycodeArg"), serial)
                kotlinx.coroutines.delay(500)
                dumpMuttonAgent(includeImage = false)
            } catch (e: Exception) {
                log("Agent", "Press key failed: ${e.message}", LogLevel.ERROR)
                null
            }
        }
    }

    suspend fun inputText(text: String, pressEnter: Boolean = true): String? {
        val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
        val jsonCmd = "{\"cmd\":\"input_text\",\"text\":\"$escapedText\",\"press_enter\":$pressEnter}"
        log("Agent", "Requesting input_text: $escapedText (enter=$pressEnter)", LogLevel.INFO)
        val response = sendToAgent(jsonCmd, silent = true)
        if (response == null || response.contains("\"status\":\"error\"")) {
            log("Agent", "Input text failed: $response", LogLevel.ERROR)
            return null
        }
        return dumpMuttonAgent(includeImage = false)
    }

    suspend fun getMuttonAgentVersion(): String? {
        var response = sendToAgent("{\"cmd\":\"version\"}", silent = true)
        if (response == null) {
            setupMuttonAgent(forceInstall = false)
            response = sendToAgent("{\"cmd\":\"version\"}", silent = true)
        }
        return try {
            if (response != null && response.isNotEmpty()) {
                val jsonObject = com.google.gson.JsonParser.parseString(response).asJsonObject
                if (jsonObject.has("version")) jsonObject.get("version").asString else null
            } else null
        } catch (_: Exception) { null }
    }

    suspend fun clearLogcatBuffer(): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) { 
            try {
                adb.adb.execute(ShellCommandRequest("logcat -c"), adb.deviceSerial)
                "Logcat cleared successfully."
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    suspend fun getFilteredLogcat(tags: List<String>, level: String, grepPattern: String, maxLines: Int, process: String = ""): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val filterSpec = if (tags.isEmpty()) {
                    "*:$level"
                } else {
                    tags.joinToString(" ") { "$it:$level" } + " *:S"
                }
                
                val grepArg = if (grepPattern.isNotBlank()) {
                    val escaped = grepPattern.replace("'", "'\\''")
                    " -e '$escaped'"
                } else ""

                // プロセスフィルタ: PID or パッケージ名 → PID解決
                val pidArg = if (process.isNotBlank()) {
                    val resolvedPid = if (process.all { it.isDigit() }) {
                        // 数字のみ → PIDとしてそのまま使う
                        process
                    } else {
                        // パッケージ名 → ProcessNameResolverで逆引き
                        val cachedPids = ProcessNameResolver.findPidsByPackageName(process)
                        if (cachedPids.isNotEmpty()) {
                            cachedPids.first() // logcat --pid は単一PIDのみ対応
                        } else {
                            // キャッシュに無い場合は pidof コマンドでフォールバック
                            try {
                                val pidofResult = adb.adb.execute(
                                    ShellCommandRequest("pidof $process"), serial
                                ).output.trim()
                                pidofResult.split(Regex("\\s+")).firstOrNull() ?: ""
                            } catch (_: Exception) { "" }
                        }
                    }
                    if (resolvedPid.isNotBlank()) " --pid=$resolvedPid" else ""
                } else ""
                
                // -t restricts output to maxLines at the source. -e performs grep at the source.
                val cmd = "logcat -d -t $maxLines -v threadtime$pidArg$grepArg $filterSpec"
                val response = adb.adb.execute(ShellCommandRequest(cmd), serial).output
                
                response.trim()
            } catch (e: Exception) {
                log("ADB", "Failed to get logcat: ${e.message}", LogLevel.ERROR)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun getDeviceInfo(): String {
        if (!adbState.value.isValid) return "{}"
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val model = adb.adb.execute(ShellCommandRequest("getprop ro.product.model"), serial).output.trim()
                val osVersion = adb.adb.execute(ShellCommandRequest("getprop ro.build.version.sdk"), serial).output.trim()
                val abi = adb.adb.execute(ShellCommandRequest("getprop ro.product.cpu.abi"), serial).output.trim()
                val screenSize = adb.adb.execute(ShellCommandRequest("wm size"), serial).output.trim().replace("Physical size: ", "")
                
                val json = com.google.gson.JsonObject()
                json.addProperty("model", model)
                json.addProperty("os_version", osVersion)
                json.addProperty("abi", abi)
                json.addProperty("screen_size", screenSize)
                json.toString()
            } catch (e: Exception) {
                log("ADB", "Failed to get device info: ${e.message}", LogLevel.ERROR)
                "{}"
            }
        }
    }

    suspend fun getDeviceState(): String {
        if (!adbState.value.isValid) return "{}"
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val powerOut = adb.adb.execute(ShellCommandRequest("dumpsys power"), serial).output
                val isAwake = powerOut.contains("mWakefulness=Awake")
                
                val windowOut = adb.adb.execute(ShellCommandRequest("dumpsys window"), serial).output
                val isLocked = windowOut.contains("isKeyguardShowing=true") || windowOut.contains("mShowingLockscreen=true")
                
                val activityOut = adb.adb.execute(ShellCommandRequest("dumpsys activity activities"), serial).output
                val resumedLine = activityOut.lines().firstOrNull { it.contains("ResumedActivity") } ?: ""
                val fgPackageRegex = Regex(" ([a-zA-Z0-9_\\.]+)/")
                val fgPackageMatch = fgPackageRegex.find(resumedLine)
                val fgPackage = fgPackageMatch?.groupValues?.get(1) ?: "unknown"
                
                val json = com.google.gson.JsonObject()
                json.addProperty("is_screen_on", isAwake)
                json.addProperty("is_locked", isLocked)
                json.addProperty("foreground_package", fgPackage)
                json.toString()
            } catch (e: Exception) {
                log("ADB", "Failed to get device state: ${e.message}", LogLevel.ERROR)
                "{}"
            }
        }
    }

    suspend fun openSettings(panel: String, packageName: String? = null): String? {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val intentBuilder = StringBuilder("am start -a ")
                when (panel.uppercase()) {
                    "ROOT" -> intentBuilder.append("android.settings.SETTINGS")
                    "SECURITY" -> intentBuilder.append("android.settings.SECURITY_SETTINGS")
                    "WIFI" -> intentBuilder.append("android.settings.WIFI_SETTINGS")
                    "DEVELOPER" -> intentBuilder.append("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                    "APP_DETAILS" -> {
                        intentBuilder.append("android.settings.APPLICATION_DETAILS_SETTINGS")
                        if (packageName != null) {
                            intentBuilder.append(" -d package:$packageName")
                        } else {
                            return@withContext "Error: APP_DETAILS requires package_name parameter."
                        }
                    }
                    else -> return@withContext "Error: Unknown panel '$panel'."
                }
                log("ADB", "Opening settings $panel...", LogLevel.INFO)
                val response = adb.adb.execute(ShellCommandRequest(intentBuilder.toString()), serial)
                if (response.exitCode == 0) {
                    delay(1000) // Wait for UI transition
                    dumpMuttonAgent(includeImage = false) // return the latest UI Dump
                } else {
                    log("ADB", "Open settings failed: ${response.output}", LogLevel.ERROR)
                    "Error: ${response.output}"
                }
            } catch (e: Exception) {
                log("ADB", "Failed to open settings: ${e.message}", LogLevel.ERROR)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun pushFile(hostPath: String, devicePath: String, useRoot: Boolean = false): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            val serial = adb.deviceSerial
            val localFile = File(hostPath)
            if (!localFile.exists()) return@withContext "Error: Local file not found: $hostPath"
            
            val tempRemoteDir = "/data/local/tmp/.smartpush"
            val tempRemotePath = "$tempRemoteDir/temp_push"
            
            try {
                val remoteDestPath = if (useRoot) {
                    adb.adb.execute(ShellCommandRequest("mkdir -p $tempRemoteDir && chmod 777 $tempRemoteDir"), serial)
                    tempRemotePath
                } else {
                    devicePath
                }
                
                log("ADB", "Pushing $hostPath to $remoteDestPath...", LogLevel.INFO)
                val pushChannel = adb.adb.execute(PushFileRequest(localFile, remoteDestPath), this, serial)
                for (progress in pushChannel) {}
                
                if (useRoot) {
                    log("ADB", "Moving temp file to protected location...", LogLevel.INFO)
                    val escapedDest = devicePath.replace("'", "'\\''")
                    val mvResult = executeWithSu("mv $tempRemotePath '$escapedDest' && chmod 644 '$escapedDest'", serial)
                    
                    adb.adb.execute(ShellCommandRequest("rm -rf $tempRemoteDir"), serial)
                    
                    if (mvResult.exitCode != 0) {
                        return@withContext "Error: Failed to move file to destination: ${mvResult.output}"
                    }
                }
                
                log("ADB", "Push complete.", LogLevel.PASS)
                "Success: File pushed to $devicePath"
            } catch (e: Exception) {
                log("ADB", "Push failed: ${e.message}", LogLevel.ERROR)
                if (useRoot) {
                    try {
                        adb.adb.execute(ShellCommandRequest("rm -rf $tempRemoteDir"), serial)
                    } catch (_: Exception) {}
                }
                "Error: ${e.message}"
            }
        }
    }

    suspend fun pullFile(devicePath: String, hostPath: String, useRoot: Boolean = false): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            val serial = adb.deviceSerial
            val localFile = File(hostPath)
            if (!localFile.parentFile.exists()) {
                localFile.parentFile.mkdirs()
            }
            
            val tempRemoteDir = "/data/local/tmp/.smartpull"
            val tempRemotePath = "$tempRemoteDir/temp_pull"
            
            try {
                val remoteSourcePath = if (useRoot) {
                    log("ADB", "Copying protected file to temp location...", LogLevel.INFO)
                    val escapedSrc = devicePath.replace("'", "'\\''")
                    val cpResult = executeWithSu("mkdir -p $tempRemoteDir && chmod 777 $tempRemoteDir && cp '$escapedSrc' $tempRemotePath && chmod 666 $tempRemotePath", serial)
                    if (cpResult.exitCode != 0) {
                        val errMsg = "Error: Failed to copy file to temp directory (exit ${cpResult.exitCode}): ${cpResult.output.trim()}"
                        log("ADB", errMsg, LogLevel.ERROR)
                        return@withContext errMsg
                    }
                    tempRemotePath
                } else {
                    devicePath
                }
                
                log("ADB", "Pulling $remoteSourcePath to $hostPath...", LogLevel.INFO)
                val channel = adb.adb.execute(PullFileRequest(remoteSourcePath, localFile), this, serial)
                for (progress in channel) {}
                
                if (useRoot) {
                    adb.adb.execute(ShellCommandRequest("rm -rf $tempRemoteDir"), serial)
                }
                
                log("ADB", "Pull complete.", LogLevel.PASS)
                "Success: File pulled to $hostPath"
            } catch (e: Exception) {
                e.printStackTrace()
                val trace = e.stackTraceToString()
                log("ADB", "Pull failed: ${e.message}\n$trace", LogLevel.ERROR)
                if (useRoot) {
                    try {
                        adb.adb.execute(ShellCommandRequest("rm -rf $tempRemoteDir"), serial)
                    } catch (_: Exception) {}
                }
                "Error: ${e.message}"
            }
        }
    }

    suspend fun installApp(apkPath: String, reinstall: Boolean = true): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val localApk = File(apkPath)
                if (!localApk.exists()) return@withContext "Error: APK not found: $apkPath"
                
                val remoteTmp = "/data/local/tmp/${localApk.name}"
                log("ADB", "Pushing APK to $remoteTmp...", LogLevel.INFO)
                
                val pushChannel = adb.adb.execute(PushFileRequest(localApk, remoteTmp), this, serial)
                for (p in pushChannel) {}
                
                log("ADB", "Installing APK...", LogLevel.INFO)
                val flags = if (reinstall) "-r " else ""
                val installResult = adb.adb.execute(ShellCommandRequest("pm install $flags-t $remoteTmp"), serial)
                
                // Cleanup tmp file
                adb.adb.execute(ShellCommandRequest("rm $remoteTmp"), serial)
                
                if (installResult.output.contains("Success")) {
                    log("ADB", "Install complete: ${localApk.name}", LogLevel.PASS)
                    "Success: Installed successfully"
                } else {
                    log("ADB", "Install failed: ${installResult.output}", LogLevel.ERROR)
                    installResult.output
                }
            } catch (e: Exception) {
                log("ADB", "Install exception: ${e.message}", LogLevel.ERROR)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun uninstallApp(packageName: String, keepData: Boolean = false): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                log("ADB", "Uninstalling package: $packageName", LogLevel.INFO)
                val flags = if (keepData) "-k " else ""
                val result = adb.adb.execute(ShellCommandRequest("pm uninstall $flags$packageName"), serial)
                
                if (result.output.contains("Success")) {
                    log("ADB", "Uninstall complete: $packageName", LogLevel.PASS)
                    "Success: Uninstalled successfully"
                } else {
                    log("ADB", "Uninstall failed (or not installed): ${result.output}", LogLevel.WARN)
                    result.output
                }
            } catch (e: Exception) {
                log("ADB", "Uninstall exception: ${e.message}", LogLevel.ERROR)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun listDirectory(path: String, useRoot: Boolean = false): List<AdbFile> {
        if (!adbState.value.isValid) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val escapedPath = path.replace("'", "'\\''")
                
                log("ADB", "Listing directory $path (root=$useRoot)...", LogLevel.INFO)
                val response = if (useRoot) {
                    executeWithSu("ls -al '$escapedPath'", serial)
                } else {
                    adb.adb.execute(ShellCommandRequest("ls -al '$escapedPath'"), serial)
                }
                
                parseLsOutput(response.output)
            } catch (e: Exception) {
                log("ADB", "Error listing directory: ${e.message}", LogLevel.ERROR)
                throw e
            }
        }
    }

    private fun parseLsOutput(output: String): List<AdbFile> {
        val files = mutableListOf<AdbFile>()
        val lines = output.lines()
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("total")) continue
            
            val tokens = trimmed.split(Regex("""\s+"""))
            if (tokens.size < 2) continue
            
            val permissions = tokens[0]
            if (permissions.length != 10) continue
            
            val isDirectory = permissions.startsWith("d")
            val isLink = permissions.startsWith("l")
            
            var size = 0L
            var dateStr = ""
            var fullName = ""
            
            if (tokens.contains("?")) {
                if (tokens.size >= 7) {
                    val nameTokens = tokens.subList(6, tokens.size)
                    fullName = nameTokens.joinToString(" ")
                } else {
                    fullName = tokens.last()
                }
                dateStr = "?"
                size = 0L
            } else {
                if (tokens.size < 8) continue
                size = tokens.getOrNull(4)?.toLongOrNull() ?: 0L
                dateStr = "${tokens.getOrNull(5) ?: ""} ${tokens.getOrNull(6) ?: ""}".trim()
                val nameTokens = tokens.subList(7, tokens.size)
                fullName = nameTokens.joinToString(" ")
            }
            
            var name = fullName
            var linkTarget: String? = null
            
            if (isLink && fullName.contains(" -> ")) {
                val parts = fullName.split(" -> ")
                name = parts[0]
                linkTarget = parts.getOrNull(1)
            }
            
            // Clean up escapes and quotes from ls output (e.g., X509\ Package.md -> X509 Package.md)
            name = name.replace("\\ ", " ")
            if (name.startsWith("'") && name.endsWith("'") && name.length >= 2) {
                name = name.substring(1, name.length - 1)
            }
            
            if (linkTarget != null) {
                linkTarget = linkTarget.replace("\\ ", " ")
                if (linkTarget.startsWith("'") && linkTarget.endsWith("'") && linkTarget.length >= 2) {
                    linkTarget = linkTarget.substring(1, linkTarget.length - 1)
                }
            }
            
            if (name == "." || name == "..") continue
            
            files.add(
                AdbFile(
                    name = name,
                    isDirectory = isDirectory,
                    size = size,
                    permissions = permissions,
                    lastModified = dateStr,
                    isSymbolicLink = isLink,
                    linkTarget = linkTarget
                )
            )
        }
        
        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun getFilePreview(path: String, useRoot: Boolean = false): PreviewData {
        if (!adbState.value.isValid) return PreviewData(false, "Error: No device connected.")
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val escapedPath = path.replace("'", "'\\''")
                
                val fileCmd = if (useRoot) {
                    executeWithSu("file '$escapedPath'", serial)
                } else {
                    adb.adb.execute(ShellCommandRequest("file '$escapedPath'"), serial)
                }
                val fileType = if (fileCmd.exitCode == 0 && fileCmd.output.isNotBlank()) {
                    fileCmd.output.trim().substringAfter(":").trim()
                } else null
                
                if (fileType != null && fileType.contains("symbolic link to", ignoreCase = true)) {
                    return@withContext PreviewData(
                        isBinary = false,
                        textContent = "File: $path\nType: $fileType",
                        hexDumpLines = null,
                        fileType = fileType
                    )
                }
                
                val isText = (fileType != null && fileType.contains("text", ignoreCase = true)) || isTextFile(path)
                
                if (isText) {
                    log("ADB", "Fetching text preview for $path...", LogLevel.INFO)
                    val response = if (useRoot) {
                        executeWithSu("head -c 4096 '$escapedPath'", serial)
                    } else {
                        adb.adb.execute(ShellCommandRequest("head -c 4096 '$escapedPath'"), serial)
                    }
                    PreviewData(
                        isBinary = false,
                        textContent = response.output,
                        hexDumpLines = null,
                        fileType = fileType
                    )
                } else {
                    log("ADB", "Fetching hex preview via base64 for $path...", LogLevel.INFO)
                    val response = if (useRoot) {
                        executeWithSu("head -c 2048 '$escapedPath' | base64", serial)
                    } else {
                        adb.adb.execute(ShellCommandRequest("head -c 2048 '$escapedPath' | base64"), serial)
                    }
                    
                    val cleanedOutput = response.output.trim()
                    
                    if (response.exitCode != 0 || cleanedOutput.isBlank()) {
                        PreviewData(
                            isBinary = true,
                            textContent = "Preview unavailable (Failed to read binary file)",
                            hexDumpLines = null,
                            fileType = fileType
                        )
                    } else {
                        val cleanedBase64 = cleanedOutput.replace(Regex("""\s+"""), "")
                        val isBase64 = cleanedBase64.matches(Regex("""^[A-Za-z0-9+/]*={0,2}$"""))
                        
                        if (isBase64) {
                            try {
                                val bytes = java.util.Base64.getDecoder().decode(cleanedBase64)
                                val hexString = formatHexDump(bytes)
                                PreviewData(
                                    isBinary = true,
                                    textContent = null,
                                    hexDumpLines = hexString.lines().filter { it.isNotBlank() },
                                    fileType = fileType
                                )
                            } catch (ex: Exception) {
                                PreviewData(
                                    isBinary = true,
                                    textContent = "Error decoding preview: ${ex.message}\nRaw output: ${cleanedOutput.take(500)}",
                                    hexDumpLines = null,
                                    fileType = fileType
                                )
                            }
                        } else {
                            PreviewData(
                                isBinary = false,
                                textContent = "Failed to preview file as binary.\nOutput/Error message:\n$cleanedOutput",
                                hexDumpLines = null,
                                fileType = fileType
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                log("ADB", "Error fetching file preview: ${e.message}", LogLevel.ERROR)
                PreviewData(
                    isBinary = false,
                    textContent = "Error loading preview: ${e.message}",
                    hexDumpLines = null
                )
            }
        }
    }

    private fun formatHexDump(bytes: ByteArray): String {
        val builder = java.lang.StringBuilder()
        for (i in bytes.indices step 16) {
            val chunkLength = minOf(16, bytes.size - i)
            val chunk = bytes.copyOfRange(i, i + chunkLength)
            
            // Offset (4 hex chars = 2 bytes)
            builder.append(String.format("%04X  ", i))
            
            // Hex string (16 bytes)
            for (j in 0 until 16) {
                if (j < chunkLength) {
                    builder.append(String.format("%02X ", chunk[j]))
                } else {
                    builder.append("   ")
                }
                if (j == 7) {
                    builder.append(" ")
                }
            }
            
            builder.append(" |")
            
            // ASCII string
            for (j in 0 until chunkLength) {
                val c = chunk[j].toInt()
                if (c in 32..126) {
                    builder.append(c.toChar())
                } else {
                    builder.append('.')
                }
            }
            builder.append("|\n")
        }
        return builder.toString()
    }

    suspend fun deleteFile(path: String, useRoot: Boolean = false): String {
        if (!adbState.value.isValid) return "Error: No device connected."
        return withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                val escapedPath = path.replace("'", "'\\''")
                log("ADB", "Deleting file $path (root=$useRoot)...", LogLevel.INFO)
                val response = if (useRoot) {
                    executeWithSu("rm -f '$escapedPath'", serial)
                } else {
                    adb.adb.execute(ShellCommandRequest("rm -f '$escapedPath'"), serial)
                }
                
                if (response.exitCode == 0) {
                    log("ADB", "Deleted successfully.", LogLevel.PASS)
                    "Success: File deleted."
                } else {
                    log("ADB", "Delete failed: ${response.output}", LogLevel.ERROR)
                    "Error: Failed to delete: ${response.output.trim()}"
                }
            } catch (e: Exception) {
                log("ADB", "Delete exception: ${e.message}", LogLevel.ERROR)
                "Error: ${e.message}"
            }
        }
    }

    private fun isTextFile(path: String): Boolean {
        val textExtensions = listOf(".txt", ".xml", ".json", ".log", ".prop", ".sh", ".conf", ".properties", ".yaml", ".yml", ".ini", ".csv", ".html", ".css", ".js", ".ts", ".kt", ".java", ".gradle")
        val lower = path.lowercase()
        return textExtensions.any { lower.endsWith(it) }
    }
}

data class LogEvent(val tag: String, val message: String, val level: LogLevel)

data class AdbState(
    val isValid: Boolean = false,
    val isUnauthorized: Boolean = false,
    val deviceSerial: String = "",
    val deviceInfo: String = ""
)