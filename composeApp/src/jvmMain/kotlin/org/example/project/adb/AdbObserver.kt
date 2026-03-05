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
import org.example.project.AppViewModel
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

class AdbObserver(private val viewModel: AppViewModel) {

    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()
    private var logcatJob: Job? = null
    
    // Agent Streams
    private var screenshotStreamJob: Job? = null
    private val _screenshotStream = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val screenshotStream = _screenshotStream.asSharedFlow()

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

        // 1. プロジェクトローカル
        val localAdb = File("bin/platform-tools/$adbName")
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
            viewModel.log("SETUP", "Critical: 'adb' not found. Run the setup script for your OS.", LogLevel.ERROR)
            return false
        }
        viewModel.log("SETUP", "ADB resolved: $path", LogLevel.PASS)
        return true
    }

    suspend fun captureScreenshot() {
        if (!viewModel.uiState.value.adbIsValid) {
            viewModel.log("ADB", "Cannot take screenshot: No device connected.", LogLevel.ERROR)
            return
        }
        withContext(Dispatchers.IO) {
            try {
                viewModel.log("ADB", "Taking screenshot...", LogLevel.INFO)
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
                viewModel.log("ADB", "Screenshot saved: ${localFile.absolutePath}", LogLevel.PASS)
            } catch (e: Exception) {
                viewModel.log("ADB", "Screenshot failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun sendText(text: String) {
        if (!viewModel.uiState.value.adbIsValid) return
        if (text.isBlank()) return

        withContext(Dispatchers.IO) {
            try {
                val escapedText = text.replace(" ", "%s")
                val command = "input text $escapedText"
                val result = adb.adb.execute(ShellCommandRequest(command), adb.deviceSerial)
                if (result.exitCode == 0) {
                    viewModel.log("ADB", "Text sent: $text", LogLevel.PASS)
                } else {
                    viewModel.log("ADB", "Input Failed: ${result.output}", LogLevel.ERROR)
                }
            } catch (e: Exception) {
                viewModel.log("ADB", "Exception sending text: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun clearAppData(packageName: String) {
        if (!viewModel.uiState.value.adbIsValid) return
        try {
            val output = adb.adb.execute(ShellCommandRequest("pm clear $packageName"), adb.deviceSerial)
            if (output.output.contains("Success")) {
                viewModel.log("ADB", "Cleared app data for $packageName", LogLevel.INFO)
            }
        } catch (e: Exception) {
            viewModel.log("ADB", "Clear data failed: ${e.message}", LogLevel.ERROR)
        }
    }

    suspend fun rebootToBootloader() {
        if (!viewModel.uiState.value.adbIsValid) return
        withContext(Dispatchers.IO) {
            try {
                adb.adb.execute(RebootRequest(RebootMode.BOOTLOADER), adb.deviceSerial)
                viewModel.log("ADB", "Rebooting to bootloader...", LogLevel.PASS)
            } catch (e: Exception) {
                viewModel.log("ADB", "Reboot failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun sendKeyEvent(keyCode: Int) {
        if (!viewModel.uiState.value.adbIsValid) return
        withContext(Dispatchers.IO) {
            try {
                adb.adb.execute(ShellCommandRequest("input keyevent $keyCode"), adb.deviceSerial)
            } catch (e: Exception) {
                viewModel.log("ADB", "Key event failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    private suspend fun fetchProcessList() {
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
            viewModel.log("ADB", "Failed to fetch process list: ${e.message}", LogLevel.WARN)
        }
    }

    suspend fun startLogcat() {
        ProcessNameResolver.clear()
        fetchProcessList()

        val serial = adb.deviceSerial
        if (!viewModel.uiState.value.adbIsValid || serial.isBlank()) return
        if (logcatJob?.isActive == true) return

        logcatJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            val buffer = StringBuilder()
            try {
                //Preapre large buffer for logcat
                //adb.adb.execute(ShellCommandRequest("logcat -G 16M"), adb.deviceSerial)

                val logChannel: ReceiveChannel<String> = adb.adb.execute(
                    request = ChanneledLogcatRequest(modes = listOf(LogcatReadMode.long,)
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
                        if (line.isNotBlank()) viewModel.onLogcatReceived(line)
                        buffer.delete(0, index + 1)
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    viewModel.log("Logcat", "Stream error: ${e.message}", LogLevel.ERROR)
                }
            } finally {
                viewModel.flushLogcatBuffer()
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
        if (!viewModel.uiState.value.adbIsValid) return

        withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                viewModel.log("Agent", "Initializing Mutton Agent...", LogLevel.INFO)

                // 1. ローカルAPKの特定 (JUnitBridge.resourceDirを使用)
                // composeResources/mutton-agent.apk はビルド後、resources に配置される想定
                val localApk = File(JUnitBridge.resourceDir, "$AGENT_APK_NAME")

                if (!localApk.exists()) {
                    viewModel.log("Agent", "Agent APK not found at: ${localApk.absolutePath}. Did you build the APK?", LogLevel.ERROR)
                    return@withContext
                }

                // 2. ポートフォワード設定
                // PC: LOCAL_FORWARD_PORT -> Android: abstract socket "mutton_agent"
                viewModel.log("Agent", "Setting up port forwarding (tcp:$LOCAL_FORWARD_PORT -> localabstract:$AGENT_SOCKET_NAME)...", LogLevel.INFO)
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
                    viewModel.log("Agent", "Port forwarding failed: ${e.message}", LogLevel.WARN)
                }

                // 3. すでにエージェントプロセスが動作しているかサイレントにチェック
                // ホストアプリ再起動時に毎回 `am force-stop` を実行すると、Agent(Android Test)側の
                // UiAutomation (Accessibility Service等) の接続状態が壊れ、以降 `rootInActiveWindow` 等が
                // null を返す問題(Bug)が発生するため、ここでプロセスが生存していればキル・再配置をスキップします。
                var isAgentRunning = false
                if (!forceInstall) {
                    // Port forwarding might take a moment to be fully active. Retry a few times.
                    viewModel.log("Agent", "Pinging agent to check if already running...", LogLevel.INFO)
                    for (i in 1..5) {
                        val pingResponse = sendToAgent("{\"command\":\"ping\"}", silent = false) // Removed silent to get error logs
                        if (pingResponse != null && pingResponse.contains("\"status\":\"ok\"")) {
                            viewModel.log("Agent", "Agent is already running. Skipping deployment to preserve UiAutomation state.", LogLevel.PASS)
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
                    viewModel.log("Agent", "Agent APK is already installed. Skipping push and install.", LogLevel.INFO)
                } else {
                    // 3. APKのプッシュ
                    viewModel.log("Agent", "Pushing agent APK to device...", LogLevel.INFO)
                    val pushChannel = adb.adb.execute(
                        PushFileRequest(localApk, REMOTE_AGENT_PATH),
                        this,
                        serial
                    )
                    // 完了待ち
                    for (progress in pushChannel) {
                        // プログレス表示が必要ならここで
                    }
                    viewModel.log("Agent", "Agent pushed successfully.", LogLevel.PASS)

                    // 3.5 APKのインストール
                    viewModel.log("Agent", "Installing agent APK...", LogLevel.INFO)
                    val installResult = adb.adb.execute(ShellCommandRequest("pm install -r -t $REMOTE_AGENT_PATH"), serial)
                    if (installResult.output.contains("Success")) {
                        viewModel.log("Agent", "Agent installed successfully.", LogLevel.PASS)
                    } else {
                        viewModel.log("Agent", "Agent installation failed: ${installResult.output}", LogLevel.ERROR)
                    }
                }

                    // 5. エージェントの起動 (am instrument)
                    // バックグラウンドで実行し続けるため、コルーチンで監視する
                    viewModel.log("Agent", "Starting agent process...", LogLevel.INFO)
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        try {
                            // am instrument で androidx.test.runner.AndroidJUnitRunner を起動
                            val cmd = "am instrument -w org.example.mutton.test/androidx.test.runner.AndroidJUnitRunner"
                            adb.adb.execute(ShellCommandRequest(cmd), serial)
                        } catch (e: Exception) {
                            // プロセスが終了した場合やエラー時
                            viewModel.log("Agent", "Agent process terminated or failed: ${e.message}", LogLevel.WARN)
                        }
                    }

                    viewModel.log("Agent", "Agent start command issued.", LogLevel.PASS)
                }

                // Wait for the agent to boot up and be ready if we just started it
                if (!isAgentRunning) {
                    viewModel.log("Agent", "Waiting for agent to become responsive...", LogLevel.INFO)
                    var responsive = false
                    for (i in 1..20) { // Up to 5 seconds
                        val pingResponse = sendToAgent("{\"command\":\"ping\"}", silent = true)
                        if (pingResponse != null && pingResponse.contains("\"status\":\"ok\"")) {
                            responsive = true
                            break
                        }
                        delay(250)
                    }
                    if (responsive) {
                        viewModel.log("Agent", "Agent is now responsive and ready.", LogLevel.PASS)
                    } else {
                        viewModel.log("Agent", "Agent failed to respond after starting. Check Logcat for agent crashes.", LogLevel.ERROR)
                    }
                }

            } catch (e: Exception) {
                viewModel.log("Agent", "Setup failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun observeAdb() {
        checkDependencies()

        while (currentCoroutineContext().isActive) {
            var backgroundMonitorJob: Job? = null
            try {
                // 1. 最速検知ループ (そのまま)
                backgroundMonitorJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        if (adb.isUnauthorized) {
                            val earlySerial = adb.getSerialEarly() ?: ""
                            if (!viewModel.uiState.value.isUnauthorized) {
                                viewModel.updateAdbState(isValid = false, isUnauthorized = true,serial=earlySerial)
                            }
                        } else if (viewModel.uiState.value.isUnauthorized) {
                            // ★追加: 未認可フラグが消えた（許可された or 抜かれた）
                            // 端末が1つも見つからないなら「USBが抜かれた」と判断してDisconnectedに戻す
                            val earlySerial = adb.getSerialEarly()
                            if (earlySerial == null) {
                                //adb.isUnauthorized = false
                                viewModel.updateAdbState(isValid = false, isUnauthorized = false)
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
                    if (viewModel.uiState.value.isRunning) continue

                    if (adb.isDeviceInitialised()) {
                        try {
                            val devices = adb.adb.execute(ListDevicesRequest())
                            val currentDevice = devices.find { it.serial == adb.deviceSerial }

                            // ★ 2. 未認可(Unauthorized)の判定
                            if (currentDevice?.state == DeviceState.UNAUTHORIZED) {
                                if (!viewModel.uiState.value.isUnauthorized) {
                                    viewModel.updateAdbState(isValid = false, isUnauthorized = true)
                                }
                                continue // 未認可の場合はここでループをやり直し、echoコマンドを打たない
                            }

                            // 3. 認可済み(Device)なら echo で生存確認
                            adb.adb.execute(ShellCommandRequest("echo"), adb.deviceSerial)

                            // 成功した場合はUIを Authorized (Active) 状態に更新
                            /*if (!viewModel.uiState.value.adbIsValid || viewModel.uiState.value.isUnauthorized) {
                                adbProps = AdbProps(adb.osversion, adb.productmodel, adb.deviceSerial, adb.displayId)
                                viewModel.updateAdbState(isValid = true, isUnauthorized = false)
                            }*/
                            if (!viewModel.uiState.value.adbIsValid || viewModel.uiState.value.isUnauthorized) {
                                adbProps = AdbProps(adb.osversion, adb.productmodel, adb.deviceSerial, adb.displayId)

                                // ★ 表示用の文字列を組み立てる
                                val infoStr = """
                                    Serial: ${adbProps.serial}
                                    Model: ${adbProps.model}
                                    OS Version: Android ${adbProps.osVersion}
                                    Display ID: ${adbProps.displayId}
                                """.trimIndent()

                                viewModel.updateAdbState(
                                    isValid = true,
                                    isUnauthorized = false,
                                    serial = adbProps.serial,
                                    info = infoStr
                                )
                            }
                        } catch (e: Exception) {
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                backgroundMonitorJob?.cancel()
                // 完全な切断時
                if (viewModel.uiState.value.adbIsValid || viewModel.uiState.value.isUnauthorized) {
                    viewModel.updateAdbState(isValid = false, isUnauthorized = false)
                    stopLogcat()
                }
                delay(1000)
            }
        }
    }
    private suspend fun sendToAgent(jsonCmd: String, silent: Boolean = false): String? {
        if (!viewModel.uiState.value.adbIsValid) return null

        return withContext(Dispatchers.IO) {
            try {
                java.net.Socket("127.0.0.1", LOCAL_FORWARD_PORT).use { socket ->
                    socket.soTimeout = 5000 // dump can take longer

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
                    viewModel.log("Agent", "Communication failed: ${e.message} (Is agent running?)", LogLevel.ERROR)
                }
                null
            }
        }
    }

    suspend fun startScreenshotStream(fps: Float = 1f) {
        if (!viewModel.uiState.value.adbIsValid) return
        if (screenshotStreamJob?.isActive == true) return

        viewModel.log("Agent", "Starting screenshot stream at $fps fps...", LogLevel.INFO)
        val jsonCmd = "{\"cmd\":\"start_stream\",\"fps\":$fps}"
        
        screenshotStreamJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
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
                    viewModel.log("Agent", "Screenshot stream failed: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

    suspend fun stopScreenshotStream() {
        screenshotStreamJob?.cancel()
        screenshotStreamJob = null
        sendToAgent("{\"cmd\":\"stop_stream\"}", silent = true)
        viewModel.log("Agent", "Screenshot stream stopped.", LogLevel.INFO)
    }

    suspend fun pingMuttonAgent() {
        viewModel.log("Agent", "Pinging agent at 127.0.0.1:$LOCAL_FORWARD_PORT...", LogLevel.INFO)
        var response = sendToAgent("{\"cmd\":\"ping\"}", silent = true)
        
        if (response == null) {
            viewModel.log("Agent", "Agent not responding. Attempting auto-setup...", LogLevel.INFO)
            setupMuttonAgent(forceInstall = false)
            response = sendToAgent("{\"cmd\":\"ping\"}")
        }

        if (response != null && response.isNotEmpty()) {
            viewModel.log("Agent", "Response: $response", LogLevel.PASS)
        } else {
            viewModel.log("Agent", "No response from agent. Setup might have failed.", LogLevel.WARN)
        }
    }

    suspend fun dumpMuttonAgent(): String? {
        viewModel.log("Agent", "Requesting UI dump from agent...", LogLevel.INFO)
        var response = sendToAgent("{\"cmd\":\"dump\"}", silent = true)
        
        if (response == null) {
            viewModel.log("Agent", "Agent not responding. Attempting auto-setup...", LogLevel.INFO)
            setupMuttonAgent(forceInstall = false)
            response = sendToAgent("{\"cmd\":\"dump\"}")
        }

        if (response != null && response.isNotEmpty()) {
            viewModel.log("Agent", "Dump Success! Output size: ${response.length} chars", LogLevel.PASS)
            //viewModel.log("Agent Dump", response, LogLevel.DEBUG)
        } else {
            viewModel.log("Agent", "Dump failed or empty response. Setup might have failed.", LogLevel.WARN)
        }
        return response
    }

    suspend fun clearLogcatBuffer() {
        if (!viewModel.uiState.value.adbIsValid) return
        withContext(Dispatchers.IO) { adb.adb.execute(ShellCommandRequest("logcat -c"), adb.deviceSerial) }
    }

    suspend fun getDeviceInfo(): String {
        if (!viewModel.uiState.value.adbIsValid) return "{}"
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
                viewModel.log("ADB", "Failed to get device info: ${e.message}", LogLevel.ERROR)
                "{}"
            }
        }
    }
}