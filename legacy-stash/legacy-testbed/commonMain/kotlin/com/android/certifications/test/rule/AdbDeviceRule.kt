package com.android.certifications.test.rule

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logging
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

    val adb = AndroidDebugBridgeClientFactory().build()
    val initTimeout: Duration = Duration.ofSeconds(10)
    fun isDeviceInitialised() = ::deviceSerial.isInitialized
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
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
                base.evaluate()
            }
        }
    }
    suspend fun waitBoot(){
        runBlocking {

                loop@ for (device in adb.execute(ListDevicesRequest())) {
                    try {
                    val booted =
                        adb.execute(GetSinglePropRequest("sys.boot_completed"), device.serial)
                            .isNotBlank()
                    if (!booted) continue

                    supportedFeatures = adb.execute(FetchDeviceFeaturesRequest(device.serial))
                    if (requiredFeatures.isNotEmpty()) {
                        Assume.assumeTrue(
                            "No compatible device found for features $requiredFeatures",
                            supportedFeatures.containsAll(requiredFeatures.asList())
                        )
                    } else {
                        continue
                    }

                    } catch (e: ConnectException) {
                        logging("connecting ... ")
                        continue
                    } catch (e: RequestRejectedException) {
                        logging("rejecting ... ")
                        continue
                    }
                }
            }
        }

    private suspend fun CoroutineScope.waitForDevice(): Device {
        while (isActive) {
            try {
                loop@ for (device in adb.execute(ListDevicesRequest())) {
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
}
