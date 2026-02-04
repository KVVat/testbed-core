package org.example.project.adb

import com.malinskiy.adam.exception.RequestRejectedException
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
class AdbObserver(private val viewModel: AppViewModel) {

    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()

// 必要なimportを追加


// ...

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
    
    /**
     * デバイスにテキストを送信します。
     * @param text 送信する文字列
     */
    suspend fun sendText(text: String) {
        if (!viewModel.uiState.value.adbIsValid) {
            viewModel.log("ADB", "Cannot send text: No device connected.", LogLevel.ERROR)
            return
        }
        // スペースをADBコマンドが認識できる "%s" に置換
        val escapedText = text.replace(" ", "%s")
        val request = ShellCommandRequest("input text '$escapedText'")
        try {
            val output = adb.adb.execute(request, adb.deviceSerial)
            if (output.exitCode == 0) {
                viewModel.log("ADB", "Sent text: '$text'", LogLevel.DEBUG)
            } else {
                viewModel.log("ADB", "Failed to send text. Error: ${output.output}", LogLevel.ERROR)
            }
        } catch (e: Exception) {
            viewModel.log("ADB", "Exception while sending text: ${e.message}", LogLevel.ERROR)
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
                            // 【修正点】ここで握りつぶさず、外側のcatchへ投げる
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
}
