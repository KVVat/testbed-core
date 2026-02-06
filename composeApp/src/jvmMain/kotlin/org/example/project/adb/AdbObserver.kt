package org.example.project.adb

import androidx.lifecycle.viewModelScope
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.request.logcat.ChanneledLogcatRequest
import com.malinskiy.adam.request.logcat.LogcatReadMode
import com.malinskiy.adam.request.misc.RebootMode
import com.malinskiy.adam.request.misc.RebootRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.example.project.AppViewModel
import org.example.project.LogLevel
import org.example.project.adb.rules.AdbDeviceRule
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.malinskiy.adam.request.sync.v1.PullFileRequest
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ReceiveChannel

class AdbObserver(private val viewModel: AppViewModel) {

    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()
    private var logcatJob: Job? = null

    /**
     * スクリーンショットを撮影し、ローカルに保存します。
     */
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

                // デバイス側でキャプチャ
                adb.adb.execute(ShellCommandRequest("screencap -p $remotePath"), serial)

                // PCへ転送
                val channel = adb.adb.execute(
                    PullFileRequest(remotePath, localFile),
                    this,
                    serial
                )
                for (progress in channel) { /* 進行状況は必要に応じて処理 */ }

                // ゴミ掃除
                adb.adb.execute(ShellCommandRequest("rm $remotePath"), serial)
                viewModel.log("ADB", "Screenshot saved: ${localFile.absolutePath}", LogLevel.PASS)
            } catch (e: Exception) {
                viewModel.log("ADB", "Screenshot failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    /**
     * デバイスにテキストを送信します。
     * 空文字のチェックと、非アスキー文字（マルチバイト文字）の制限を行っています。
     */
    suspend fun sendText(text: String) {
        if (!viewModel.uiState.value.adbIsValid) return

        // 1. 空文字または空白のみの場合は、adbコマンドの発行自体をスキップしてIllegalArgumentExceptionを回避
        if (text.isBlank()) {
            viewModel.log("ADB", "Input ignored: Text is empty.", LogLevel.WARN)
            return
        }

        // 2. 非アスキー文字（Unicode > 127）が含まれているかチェック
        // 日本語だけでなく、多言語のマルチバイト文字すべてを対象にします
        val hasNonAscii = text.any { it.code > 127 }
        if (hasNonAscii) {
            viewModel.log("ADB", "Input failed: Non-ASCII characters are not supported via 'input text'.", LogLevel.ERROR)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // スペースを %s にエスケープ
                val escapedText = text.replace(" ", "%s")
                val command = "input text $escapedText"
                val result = adb.adb.execute(ShellCommandRequest(command), adb.deviceSerial)

                if (result.exitCode == 0) {
                    viewModel.log("ADB", "Text sent successfully: $text", LogLevel.PASS)
                } else {
                    viewModel.log("ADB", "Input Failed: ${result.output}", LogLevel.ERROR)
                }
            } catch (e: Exception) {
                viewModel.log("ADB", "Exception sending text: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    /**
     * アプリデータを消去します。
     */
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

    /**
     * ブートローダーモードで再起動します。
     */
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

    /**
     * キーイベントを送信します。
     */
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

    /**
     * Logcatのストリーミングを開始します。
     */
    suspend fun startLogcat() {
        val serial = adb.deviceSerial
        if (!viewModel.uiState.value.adbIsValid || serial.isBlank()) return

        if (logcatJob?.isActive == true) return

        logcatJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            val buffer = StringBuilder()
            try {
                val logChannel: ReceiveChannel<String> = adb.adb.execute(
                    request = ChanneledLogcatRequest(modes = listOf(LogcatReadMode.threadtime)),
                    serial = serial,
                    scope = this
                )

                logChannel.consumeEach { chunk ->
                    buffer.append(chunk)
                    while (buffer.contains("\n")) {
                        val index = buffer.indexOf("\n")
                        val line = buffer.substring(0, index).trimEnd('\r', '\n')
                        if (line.isNotBlank()) {
                            viewModel.onLogcatReceived(line)
                        }
                        buffer.delete(0, index + 1)
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    viewModel.log("Logcat", "Stream error: ${e.message}", LogLevel.ERROR)
                }
            } finally {
                buffer.clear()
            }
        }
    }

    /**
     * Logcatのストリーミングを停止します。
     */
    fun stopLogcat() {
        logcatJob?.cancel()
        logcatJob = null
    }

    /**
     * ADBデバイスの接続状態を監視し、自動再接続を行います。
     */
    suspend fun observeAdb() {
        while (currentCoroutineContext().isActive) {
            try {
                withContext(Dispatchers.IO) {
                    adb.startAlone()
                }

                while (currentCoroutineContext().isActive) {
                    delay(1000)
                    if (viewModel.uiState.value.isRunning) continue

                    if (adb.isDeviceInitialised()) {
                        if (!viewModel.uiState.value.adbIsValid) {
                            adbProps = AdbProps(adb.osversion, adb.productmodel, adb.deviceSerial, adb.displayId)
                            viewModel.toggleAdbIsValid(true)
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

    /**
     * デバイス側のLogcatバッファをクリアします。
     */
    suspend fun clearLogcatBuffer() {
        if (!viewModel.uiState.value.adbIsValid) return
        withContext(Dispatchers.IO) {
            adb.adb.execute(ShellCommandRequest("logcat -c"), adb.deviceSerial)
        }
    }
}