package org.example.project.adb.rules

import com.malinskiy.adam.AndroidDebugBridgeClientFactory
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.interactor.StartAdbInteractor
import com.malinskiy.adam.request.Feature
import com.malinskiy.adam.request.device.Device
import com.malinskiy.adam.request.device.FetchDeviceFeaturesRequest
import com.malinskiy.adam.request.device.ListDevicesRequest
import com.malinskiy.adam.request.misc.GetAdbServerVersionRequest
import com.malinskiy.adam.request.prop.GetSinglePropRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.net.ConnectException
import java.time.Duration

/**
 * This rule supports only one device
 *
 * If device is not found - error
 * If device doesn't have required features - assumption failure
 */
class AdbDeviceRule(val deviceType: DeviceType = DeviceType.ANY, vararg val requiredFeatures: Feature) : TestRule {

    lateinit var deviceSerial: String
    lateinit var supportedFeatures: List<Feature>
    lateinit var lineSeparator: String
    lateinit var osversion: String
    lateinit var displayId: String
    lateinit var productmodel: String

    // ★追加: 外部から参照できる未認可状態のフラグ
    var isUnauthorized: Boolean = false

    val adb = AndroidDebugBridgeClientFactory().build()
    val initTimeout: Duration = Duration.ofSeconds(3)
    fun isDeviceInitialised() = ::deviceSerial.isInitialized



    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                runBlocking {
                    val success = withTimeoutOrNull(initTimeout.toMillis()) {
                        //First we start the adb if it is not running
                        startAdb()

                        //Wait for compatible device
                        //boot + supported features
                        val device = waitForDevice()
                        deviceSerial = device.serial
                        true
                    }
                    Assume.assumeTrue(
                        "【Skip] Cannot find device.",
                        success == true
                    )
                    if(success == false){

                    }

                }
                base.evaluate()
            }
        }
    }


    private suspend fun CoroutineScope.waitForDevice(): Device {
        while (isActive) {
            try {
                val devices = adb.execute(ListDevicesRequest())

                //isUnauthorized = devices.any { it.state == com.malinskiy.adam.request.device.DeviceState.UNAUTHORIZED }
                isUnauthorized = false

                loop@ for (device in devices) {
                    if(device.state == com.malinskiy.adam.request.device.DeviceState.UNAUTHORIZED) {
                        isUnauthorized = true
                        continue
                    }

                    val booted = adb.execute(GetSinglePropRequest("sys.boot_completed"), device.serial).isNotBlank()
                    if (!booted) continue

                    when (deviceType) {
                        DeviceType.EMULATOR -> {
                            Assume.assumeTrue(
                                "No device of type $deviceType found",
                                device.serial.startsWith("emulator-")
                            )
                        }
                        DeviceType.ANY ->{
                        }
                    }

                    supportedFeatures = adb.execute(FetchDeviceFeaturesRequest(device.serial))
                    if (requiredFeatures.isNotEmpty()) {
                        Assume.assumeTrue(
                            "No compatible device found for features $requiredFeatures",
                            supportedFeatures.containsAll(requiredFeatures.asList())
                        )
                    }
                    //sdb shell getprop ro.build.version.release
                    osversion = adb.execute(
                        ShellCommandRequest("getprop ro.build.version.release"),
                        device.serial).output

                    displayId = adb.execute(
                        ShellCommandRequest("getprop ro.build.display.id"),
                        device.serial).output

                    productmodel = adb.execute(
                        ShellCommandRequest("getprop ro.product.model"),
                        device.serial).output


                    lineSeparator = adb.execute(
                        ShellCommandRequest("echo"),
                        device.serial
                    ).output

                    return device
                }
            } catch (e: ConnectException) {
                continue
            }
            //delay(1000)
        }
        throw RuntimeException("Timeout waiting for device")
    }

    suspend fun startAlone(){
        runBlocking {
            withTimeoutOrNull(initTimeout.toMillis()) {
                //First we start the adb if it is not running
                startAdb()

                //Wait for compatible device
                //boot + supported features
                val device = waitForDevice()
                deviceSerial = device.serial
            } ?: throw RuntimeException("Timeout waiting for device")
        }
    }

    suspend fun getSerialEarly(): String? {
        try {
            val devices = adb.execute(ListDevicesRequest())
            // offline ではなく device として認識された最初のものを返す
            return devices.firstOrNull { it.state == com.malinskiy.adam.request.device.DeviceState.DEVICE }?.serial
        } catch (e: Exception) {
            return null
        }
    }
    suspend fun startAdb() {
        try {
            adb.execute(GetAdbServerVersionRequest())
        } catch (e: ConnectException) {
            val success = StartAdbInteractor().execute()
            if (!success) {
                throw RuntimeException("Unable to start adb")
            }
        }
    }

    /**
    * 対象デバイスの再起動完了を安全に待機します。
    * * @param timeoutMillis 最大待機時間 (デフォルト: 90秒)
    */
    suspend fun waitBoot(timeoutMillis: Long = 90_000L) {
        // 自身のルールで保持しているデバイスシリアルを使用する
        val serial = this.deviceSerial

        withTimeout(timeoutMillis) {
            while (isActive) {
                try {
                    // 1. デバイスがオンラインリストに復帰しているか確認
                    val devices = adb.execute(ListDevicesRequest())
                    val targetDevice = devices.find { it.serial == serial }

                    if (targetDevice != null) {
                        // 2. sys.boot_completed が "1" になっているか確認
                        val bootCompleted = adb.execute(
                            GetSinglePropRequest("sys.boot_completed"),
                            serial
                        ).trim()

                        if (bootCompleted == "1") {
                            // 3. (オプション) パッケージマネージャ等の準備が完了しているか念押しで確認
                            // BFU(Before First Unlock)状態でも PM は応答するため、これがあるとより確実です
                            val pmCheck = adb.execute(
                                ShellCommandRequest("pm path android"),
                                serial
                            ).output

                            if (pmCheck.isNotBlank()) {
                                // 完全にブート完了と判定
                                return@withTimeout
                            }
                        }
                    }
                } catch (e: Exception) {
                    // adbのコネクション切断例外（再起動直後など）は握り潰してリトライ
                }

                // 2秒待ってから再チェック (CPU負荷を下げる)
                delay(2000L)
            }
        }
    }
}