package org.example.project.adb


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