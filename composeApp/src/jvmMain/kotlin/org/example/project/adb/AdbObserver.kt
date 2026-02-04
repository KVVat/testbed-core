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

class AdbObserver(private val viewModel: AppViewModel) {

    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()

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
                            viewModel.log("ADB", "Device Connected: ${adb.deviceSerial} (${adb.displayId})", LogLevel.PASS)

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

/*
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.AppViewModel
import org.example.project.adb.rules.AdbDeviceRule
import org.example.project.LogLevel

class AdbObserver(private val viewModel: AppViewModel) { // ViewModelを受け取るように変更

    // TestRuleとしてではなく、単なるヘルパーとしてインスタンス化
    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps: AdbProps = AdbProps()

    suspend fun observeAdb(): Boolean {
        withContext(Dispatchers.IO) {
            try {
                viewModel.log("ADB", "Initializing ADB client...", LogLevel.DEBUG)
                adb.startAlone() // 初回の起動試行

                while (true) {
                    Thread.sleep(1000) // ループ間隔を少し広げました(250ms -> 1000ms)

                    // テスト実行中は監視をスキップする場合
                    if (viewModel.uiState.value.isRunning) continue

                    val isDeviceInit = try {
                        adb.isDeviceInitialised()
                    } catch (e: Exception) { false }

                    try {
                        if (isDeviceInit) {
                            // 未接続状態から接続状態に変わった場合の処理
                            if (!viewModel.uiState.value.adbIsValid) {
                                viewModel.log("ADB", "Device Connected > ${adb.deviceSerial} / ${adb.displayId}", LogLevel.PASS)
                                adbProps = AdbProps(
                                    adb.osversion,
                                    adb.productmodel,
                                    adb.deviceSerial,
                                    adb.displayId
                                )
                                viewModel.toggleAdbIsValid(true)
                            }

                            // Heartbeat (echo)
                            adb.adb.execute(ShellCommandRequest("echo"), adb.deviceSerial)
                        } else {
                            // 初期化されていない場合は再接続を試みる
                            adb.startAlone()
                        }
                    } catch (rrException: RequestRejectedException) {
                        viewModel.log("ADB", "Request Rejected: ${rrException.message}", LogLevel.ERROR)
                        // リセットが必要ならここで
                        continue
                    } catch (e: Exception) {
                        // 切断検知など
                        if (viewModel.uiState.value.adbIsValid) {
                            viewModel.log("ADB", "Device Disconnected: ${e.message}", LogLevel.ERROR)
                            adbProps = AdbProps()
                            viewModel.toggleAdbIsValid(false)
                        }
                        // 再接続トライ
                        try { adb.startAlone() } catch (_: Exception) {}
                    }
                }
            } catch (anyException: Exception) {
                viewModel.log("ADB", "Fatal Observer Error: ${anyException.message}", LogLevel.ERROR)
            }
        }
        return true
    }
}
/*
class AdbObserver(private val viewModel: AppViewModel){
    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps:AdbProps = AdbProps()

    suspend fun observeAdb():Boolean{
        try {
            adb.startAlone()
            while (true) {
                withContext(Dispatchers.IO) {
                    Thread.sleep(250)
                }
                //if (viewModel.uiState.value.isRunning) continue
                val isDeviceInit = adb.isDeviceInitialised()
                try {
                    if (isDeviceInit) {
                        if (!viewModel.uiState.value.adbIsValid) {
                           //logging("Device Connected > ${adb.deviceSerial}/${adb.displayId}")
                            adbProps = AdbProps(
                                adb.osversion,
                                adb.productmodel,
                                adb.deviceSerial,
                                adb.displayId
                            )
                        }
                        viewModel.toggleAdbIsValid(true)
                        adb.adb.execute(ShellCommandRequest("echo"))
                    }
                } catch (rrException: RequestRejectedException) {
                    adb.startAlone()
                    continue
                }
            }
        } catch (anyException:Exception){
            //disable if flag is enabled
            if(viewModel.uiState.value.adbIsValid) {
                //logging("Device Disconnected > (" + anyException.localizedMessage+") #${anyException.javaClass.name}")
                adbProps = AdbProps()
                viewModel.toggleAdbIsValid(false)
            }
        }
        return true
    }
}*/