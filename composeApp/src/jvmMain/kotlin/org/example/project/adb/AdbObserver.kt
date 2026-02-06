package org.example.project.adb

import androidx.lifecycle.viewModelScope
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.request.misc.RebootMode
import com.malinskiy.adam.request.misc.RebootRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.example.project.AppViewModel // ViewModelのパッケージに合わせてください
import org.example.project.LogLevel // LogLevelのパッケージに合わせてください
import org.example.project.adb.rules.AdbDeviceRule
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.malinskiy.adam.request.sync.v1.PullFileRequest
import kotlinx.coroutines.channels.consumeEach
import java.io.FileOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.malinskiy.adam.request.logcat.ChanneledLogcatRequest
import com.malinskiy.adam.request.logcat.LogcatReadMode
import kotlinx.coroutines.channels.ReceiveChannel

class AdbObserver(private val viewModel: AppViewModel) {

    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()
    private var logcatJob: Job? = null


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

                // 保存先フォルダの作成
                val localDir = File("screenshots")
                if (!localDir.exists()) localDir.mkdirs()
                val localFile = File(localDir, "screenshot_$timestamp.png")

                // 1. デバイス側でPNG保存 (screencap)
                // -p オプションでPNG形式になります
                adb.adb.execute(ShellCommandRequest("screencap -p $remotePath"), serial)

                // 2. PCへ転送 (Pull)
                // PullFileRequest(v1) を使用します。これは ReceiveChannel<ByteArray> を返します。
                val channel = adb.adb.execute(
                    PullFileRequest(remotePath,localFile),
                    this,
                    serial
                )

                for (prgress in channel) {
                    //outputStream.write(chunk)
                }

                // 3. ストリームからファイルへ書き出し
                // useブロックを使うことで、処理終了後に自動でストリームを閉じます
                //FileOutputStream(localFile).use { outputStream ->
                    // channelからデータ(chunk)が来るたびに書き込
                //}

                // 4. デバイス側のゴミ掃除
                adb.adb.execute(ShellCommandRequest("rm $remotePath"), serial)

                viewModel.log("ADB", "Screenshot saved: ${localFile.absolutePath}", LogLevel.PASS)

            } catch (e: Exception) {
                // エラー詳細を出力
                viewModel.log("ADB", "Screenshot failed: ${e.javaClass.simpleName} - ${e.message}", LogLevel.ERROR)
                e.printStackTrace() // デバッグ用
            }
        }
    }
    
    // --- Text Input (修正版) ---
    suspend fun sendText(text: String) {
        if (!viewModel.uiState.value.adbIsValid) return
        withContext(Dispatchers.IO) {
            try {
                // スペースを %s に置換
                val escapedText = text.replace(" ", "%s")
                val command = "input text $escapedText"

                viewModel.log("ADB", "Sending text: $text", LogLevel.INFO)

                // 実行結果を受け取る
                val result = adb.adb.execute(ShellCommandRequest(command), adb.deviceSerial)

                // exitCode 0 = 成功
                if (result.exitCode == 0) {
                    // outputが空でなければ補足情報として表示
                    if (result.output.isNotBlank()) {
                        viewModel.log("ADB", "Input Result: ${result.output}", LogLevel.DEBUG)
                    }
                    else {
                        viewModel.log("ADB", "Text sent successfully.", LogLevel.PASS)
                    }
                } else {
                    // エラー時は赤文字で詳細を表示
                    viewModel.log("ADB", "Input Failed (Code ${result.exitCode}): ${result.output}", LogLevel.ERROR)
                }
            } catch (e: Exception) {
                viewModel.log("ADB", "Exception sending text: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    /**
     * 指定されたパッケージのアプリケーションデータを消去します。
     * @param packageName アプリのパッケージ名
     */
    suspend fun clearAppData(packageName: String) {
        if (!viewModel.uiState.value.adbIsValid) {
            viewModel.log("ADB", "Cannot clear app data: No device connected.", LogLevel.ERROR)
            return
        }
        val request = ShellCommandRequest("pm clear $packageName")
        try {
            val output = adb.adb.execute(request, adb.deviceSerial)
            if (output.output.contains("Success")) {
                viewModel.log("ADB", "Cleared app data for $packageName", LogLevel.INFO)
            } else {
                viewModel.log("ADB", "Failed to clear app data. Error: ${output.output}", LogLevel.ERROR)
            }
        } catch (e: Exception) {
            viewModel.log("ADB", "Exception while clearing app data: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * デバイスをブートローダーモードで再起動します。
     */
    suspend fun rebootToBootloader() {
        if (!viewModel.uiState.value.adbIsValid) {
            viewModel.log("ADB", "Cannot reboot to bootloader: No device connected.", LogLevel.ERROR)
            return
        }
        withContext(Dispatchers.IO) {
            try {
                viewModel.log("ADB", "Rebooting to bootloader...", LogLevel.INFO)
                adb.adb.execute(RebootRequest(RebootMode.BOOTLOADER), adb.deviceSerial)
                viewModel.log("ADB", "Reboot command sent.", LogLevel.PASS)
            } catch (e: Exception) {
                viewModel.log("ADB", "Failed to reboot to bootloader: ${e.message}", LogLevel.ERROR)
            }
        }
    }
    // --- Key Events ---
    suspend fun sendKeyEvent(keyCode: Int) {
        if (!viewModel.uiState.value.adbIsValid) return
        withContext(Dispatchers.IO) {
            try {
                viewModel.log("ADB", "Sending Key Event: $keyCode", LogLevel.INFO)
                adb.adb.execute(ShellCommandRequest("input keyevent $keyCode"), adb.deviceSerial)
            } catch (e: Exception) {
                viewModel.log("ADB", "Failed to send key $keyCode: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    /**
     * Logcatのストリームを開始し、ViewModelに各行を渡します。
     */
    suspend fun startLogcat() {
        val serial = adb.deviceSerial
        if (!viewModel.uiState.value.adbIsValid || serial.isBlank()) {
            viewModel.log("Logcat", "Cannot start logcat: No device connected or serial unknown.", LogLevel.ERROR)
            return
        }
        if (logcatJob?.isActive == true) {
            viewModel.log("Logcat", "Logcat is already running.", LogLevel.INFO)
            return
        }

        logcatJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                // ChanneledLogcatRequestを使用し、execute()でReceiveChannel<String>としてログを受け取る
                val logChannel: ReceiveChannel<String> = adb.adb.execute(
                    request = ChanneledLogcatRequest(modes = listOf(LogcatReadMode.threadtime)),
                    serial = serial,
                    scope = this
                )
                logChannel.consumeEach { line -> // ReceiveChannelをconsumeEachで処理
                    if (line.isNotBlank()) {
                        viewModel.onLogcatReceived(line)
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    viewModel.log("Logcat", "Logcat stream error: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

    /**
     * 実行中のLogcatストリームを停止します。
     */
    fun stopLogcat() {
        logcatJob?.cancel()
        logcatJob = null
    }
    
    suspend fun observeAdb() {
        // コルーチンがキャンセルされない限り、永遠に「接続待ち」と「監視」を繰り返す
        while (currentCoroutineContext().isActive) {
            try {
                // ==========================================
                // 1. 接続待機フェーズ
                // ==========================================
                // ここでデバイスが見つかるまでブロック（または待機）されます
                // AdbDeviceRuleの実装によってはブロッキングの可能性があるためIOディスパッチャ推奨
                withContext(Dispatchers.IO) {
                    // startAlone() はデバイスを見つけるかタイムアウトするまで戻ってこない想定
                    adb.startAlone()
                }

                // ==========================================
                // 2. 監視フェーズ (Connected Loop)
                // ==========================================
                while (currentCoroutineContext().isActive) {
                    // ループ間隔 (負荷軽減)
                    delay(1000)

                    // テスト実行中などは監視をスキップするガード（必要に応じて）
                    if (viewModel.uiState.value.isRunning) continue

                    // 初期化チェック
                    val isDeviceInit = try {
                        adb.isDeviceInitialised()
                    } catch (e: Exception) { false }

                    if (isDeviceInit) {
                        // 未接続 -> 接続 への状態変化検知
                        if (!viewModel.uiState.value.adbIsValid) {
                            viewModel.log("ADB", "Device Connected: ${adb.deviceSerial} (${adb.displayId.trimEnd()})", LogLevel.PASS)

                            adbProps = AdbProps(
                                adb.osversion,
                                adb.productmodel,
                                adb.deviceSerial,
                                adb.displayId
                            )
                            viewModel.toggleAdbIsValid(true)
                        }

                        // Heartbeat (Echo) 送信
                        try {
                            // ここでコマンドを実行し、失敗したら RequestRejectedException 等が出る
                            adb.adb.execute(ShellCommandRequest("echo"), adb.deviceSerial)
                        } catch (rrException: RequestRejectedException) {
                            // 【修正点】ここで握つぶさず、外側のcatchへ投げる
                            throw rrException
                        }
                    } else {
                        // デバイス変数が初期化されていない場合、再接続のために例外を投げる
                        throw Exception("Device not initialized")
                    }
                }

            } catch (e: Exception) {
                // ==========================================
                // 3. 切断・再試行フェーズ (Outer Catch)
                // ==========================================
                // RequestRejectedException もここに到達します

                // UIが「接続済み」だった場合のみ、切断ログを出す
                if (viewModel.uiState.value.adbIsValid) {
                    viewModel.log("ADB", "Device Disconnected: ${e.message}", LogLevel.ERROR)

                    // 状態をリセット
                    adbProps = AdbProps()
                    viewModel.toggleAdbIsValid(false)
                }

                // 少し待ってから、一番外側のループ(while)の先頭に戻り、
                // 再び adb.startAlone() で接続待ちに入ります
                delay(2000)
            }
        }
    }

    /**
     * 端末側のLogcatバッファを空にします (adb logcat -c)
     */
    suspend fun clearLogcatBuffer() {
        if (!viewModel.uiState.value.adbIsValid) return

        withContext(Dispatchers.IO) {
            try {
                viewModel.log("ADB", "Clearing device logcat buffer...", LogLevel.INFO)

                // "logcat -c" でバッファをクリア
                adb.adb.execute(ShellCommandRequest("logcat -c"), adb.deviceSerial)

                viewModel.log("ADB", "Logcat buffer cleared.", LogLevel.PASS)
            } catch (e: Exception) {
                viewModel.log("ADB", "Failed to clear logcat: ${e.message}", LogLevel.ERROR)
            }
        }
    }
}
