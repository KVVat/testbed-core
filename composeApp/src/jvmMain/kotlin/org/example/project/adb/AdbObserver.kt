package org.example.project.adb

import androidx.lifecycle.viewModelScope
import com.malinskiy.adam.exception.RequestRejectedException
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AdbObserver(private val viewModel: AppViewModel) {

    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()
    private var logcatJob: Job? = null

    // Mutton Agentの設定
    private val AGENT_JAR_NAME = "mutton-agent.jar"
    private val REMOTE_AGENT_PATH = "/data/local/tmp/$AGENT_JAR_NAME"
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
                val logChannel: ReceiveChannel<String> = adb.adb.execute(
                    request = ChanneledLogcatRequest(modes = listOf(LogcatReadMode.long)),
                    serial = serial,
                    scope = this
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
    suspend fun setupMuttonAgent() {
        if (!viewModel.uiState.value.adbIsValid) return

        withContext(Dispatchers.IO) {
            try {
                val serial = adb.deviceSerial
                viewModel.log("Agent", "Initializing Mutton Agent...", LogLevel.INFO)

                // 1. ローカルJARの特定 (JUnitBridge.resourceDirを使用)
                // composeResources/mutton-agent.jar はビルド後、resources に配置される想定
                val localJar = File(JUnitBridge.resourceDir, "$AGENT_JAR_NAME")

                if (!localJar.exists()) {
                    viewModel.log("Agent", "Agent Jar not found at: ${localJar.absolutePath}. Did you run :makeAgentJar?", LogLevel.ERROR)
                    return@withContext
                }

                // 2. 既存プロセスのクリーンアップ
                // "pkill" は一部のAndroidバージョンで使えないため、killallやps grepを使用
                // ここでは簡易的に pkill を試みる (失敗しても次へ)
                try {
                    adb.adb.execute(ShellCommandRequest("pkill -f $AGENT_JAR_NAME"), serial)
                } catch (_: Exception) {}

                // 3. JARのプッシュ
                viewModel.log("Agent", "Pushing agent to device...", LogLevel.INFO)
                val pushChannel = adb.adb.execute(
                    PushFileRequest(localJar, REMOTE_AGENT_PATH),
                    this,
                    serial
                )
                // 完了待ち
                for (progress in pushChannel) {
                    // プログレス表示が必要ならここで
                }
                viewModel.log("Agent", "Agent pushed successfully.", LogLevel.PASS)

                // 4. ポートフォワード設定
                // PC: LOCAL_FORWARD_PORT -> Android: abstract socket "mutton_agent"
                viewModel.log("Agent", "Setting up port forwarding (tcp:$LOCAL_FORWARD_PORT -> localabstract:$AGENT_SOCKET_NAME)...", LogLevel.INFO)

                adb.adb.execute(
                    PortForwardRequest(
                        // PC側: TCPポート 11451
                        local = LocalTcpPortSpec(LOCAL_FORWARD_PORT),
                        remote = RemoteAbstractPortSpec(AGENT_SOCKET_NAME),
                        serial = serial
                    )
                )


                // 5. エージェントの起動 (app_process)
                // バックグラウンドで実行し続けるため、コマンドを投げるだけにするか、コルーチンで監視する
                viewModel.log("Agent", "Starting agent process...", LogLevel.INFO)
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        // CLASSPATHを設定して app_process を起動。エントリポイントは org.example.agent.Main
                        val cmd = "CLASSPATH=$REMOTE_AGENT_PATH app_process / org.example.mutton.Main"
                        adb.adb.execute(ShellCommandRequest(cmd), serial)
                    } catch (e: Exception) {
                        // プロセスが終了した場合やエラー時
                        viewModel.log("Agent", "Agent process terminated: ${e.message}", LogLevel.WARN)
                    }
                }

                viewModel.log("Agent", "Agent start command issued.", LogLevel.PASS)

            } catch (e: Exception) {
                viewModel.log("Agent", "Setup failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    suspend fun observeAdb() {
        checkDependencies()
        while (currentCoroutineContext().isActive) {
            try {
                withContext(Dispatchers.IO) { adb.startAlone() }
                while (currentCoroutineContext().isActive) {
                    delay(1000)
                    if (viewModel.uiState.value.isRunning) continue
                    if (adb.isDeviceInitialised()) {
                        if (!viewModel.uiState.value.adbIsValid) {
                            adbProps = AdbProps(adb.osversion, adb.productmodel, adb.deviceSerial, adb.displayId)
                            viewModel.toggleAdbIsValid(true)

                            // 接続時にエージェントを自動セットアップする場合はここで呼ぶ
                            setupMuttonAgent()
                        }
                        adb.adb.execute(ShellCommandRequest("echo"), adb.deviceSerial)
                    }
                }
            } catch (e: Exception) {
                if (viewModel.uiState.value.adbIsValid) {
                    viewModel.toggleAdbIsValid(false)
                    stopLogcat()
                }
                delay(2000)
            }
        }
    }

    suspend fun pingMuttonAgent() {
        if (!viewModel.uiState.value.adbIsValid) return

        withContext(Dispatchers.IO) {
            try {
                viewModel.log("Agent", "Pinging agent at 127.0.0.1:$LOCAL_FORWARD_PORT...", LogLevel.INFO)

                // 単純なSocket通信でJSONを投げつける
                java.net.Socket("127.0.0.1", LOCAL_FORWARD_PORT).use { socket ->
                    // タイムアウト設定 (詰まると嫌なので)
                    socket.soTimeout = 2000

                    val writer = java.io.PrintWriter(socket.getOutputStream(), true)
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))

                    // Pingコマンド送信
                    // エージェント側が改行区切りのJSONを待ってると想定
                    val jsonCmd = "{\"cmd\":\"ping\"}"
                    writer.println(jsonCmd)

                    // レスポンス受信
                    val response = reader.readLine()

                    if (response != null) {
                        viewModel.log("Agent", "Response: $response", LogLevel.PASS)
                        // ここで "pong" が返ってくるか判定してもOK
                    } else {
                        viewModel.log("Agent", "No response from agent.", LogLevel.WARN)
                    }
                }
            } catch (e: Exception) {
                viewModel.log("Agent", "Ping failed: ${e.message} (Is agent running?)", LogLevel.ERROR)
            }
        }
    }

    suspend fun clearLogcatBuffer() {
        if (!viewModel.uiState.value.adbIsValid) return
        withContext(Dispatchers.IO) { adb.adb.execute(ShellCommandRequest("logcat -c"), adb.deviceSerial) }
    }
}