package com.android.certifications.test

import Platform
import com.android.certifications.test.rule.AdbDeviceRule
import com.android.certifications.test.utils.AdamUtils
import com.android.certifications.test.utils.HostShellHelper
import com.android.certifications.test.utils.SFR
import com.android.certifications.test.utils.output_path
import com.android.certifications.test.utils.resource_path
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import logging
import org.junit.Rule
import java.io.File
import java.nio.file.Paths


@SFR("Mutton Test","The test for uiautomutton connect","automutton")
class MuttonTest {

    //How to run the httpserver on the android device
    //>> ./gradlew uiserver:assembleRelease
    //>> ./gradlew uiserver:assembleAndroidTest
    //>> adb install ./uiserver/build/outputs/apk/release/uiserver-release.apk
    //>> adb install ./uiserver/build/outputs/apk/androidTest/release/uiserver-release-androidTest.apk
    //>> adb shell pm list instrumentation
    //>> adb forward tcp:9008 tcp:9008
    //>> adb shell am instrument -w com.github.uiautomutton.test/androidx.test.runner.AndroidJUnitRunner

    //stop instrumentation test case
    // call unavailable test
    //adb shell am instrument -e class com.github.uiautomutton.test#dummy com.github.uiautomutton.test/androidx.test.runner.AndroidJUnitRunner
    // or
    //adb shell am force-stop com.github.uiautomutton.test

    @Rule
    @JvmField
    val adb: AdbDeviceRule = AdbDeviceRule()
    private val client: AndroidDebugBridgeClient = adb.adb;


    private val file_server: File =
        File(Paths.get(resource_path(),"muttons","uiserver-release.apk").toUri())
    private val file_instrument: File =
        File(Paths.get(resource_path(),"muttons","uiserver-release-androidTest.apk").toUri())

    private val INSTRUMENT_PACKAGE = "com.github.uiautomutton.test/androidx.test.runner.AndroidJUnitRunner"
    @Before
    fun setUp()
    {
        runBlocking {
            AdamUtils.InstallApk(file_server,true,adb);
            AdamUtils.InstallApk(file_instrument,true,adb);
        }

    }
    @After
    fun teardown() {
        runBlocking {

        }
    }

    @Test
    fun testOutput() {
        runBlocking{

            logging(resource_path())
            logging(output_path())
            Thread.sleep(500)
            //var r = AdamUtils.shellRequest("pm list instrumentation",adb);
            //Thread.sleep(500)
            //logging(">"+r.output)
            var r = AdamUtils.shellRequest("forward tcp:9008 tcp:9008",adb);
            Thread.sleep(500)
            //logging(">"+r.output)
            Thread.sleep(500)
            //Need ChanneledShellCommandRequest to track output
            r = AdamUtils.shellRequest("am instrument -w $INSTRUMENT_PACKAGE",adb);
            logging(">>>"+r.output)

            //Thread.sleep(1000*500)

        }
        logging("ClassName"+this.javaClass.canonicalName);
    }
}