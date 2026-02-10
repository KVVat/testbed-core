package com.android.certifications.test

import com.android.certifications.test.rule.AdbDeviceRule
import com.android.certifications.test.utils.AdamUtils
import com.android.certifications.test.utils.LogcatResult
import com.android.certifications.test.utils.SFR
import com.android.certifications.test.utils.TestAssertLogger
import com.android.certifications.test.utils.resource_path
import com.malinskiy.adam.request.misc.KillAdbRequest
import com.malinskiy.adam.request.misc.RebootRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import logging
import org.hamcrest.MatcherAssert
import org.hamcrest.core.IsEqual
import org.hamcrest.core.StringStartsWith
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.rules.TestName
import java.io.File
import java.nio.file.Paths

@SFR("FCS_CKH.1/Low", """
  FCS_CKH.1.1/Low The TSF shall support a key hierarchy for the data encryption key(s) 
  for Low user data assets.
  
  FCS_CKH.1.2/Low The TSF shall ensure that all keys in the key hierarchy are derived and/or 
  generated according to [assignment: description of how each key in the hierarchy is derived and/or
  generated, with which key lengths and according to which standards] ensuring that the key hierarchy
  uses the DUK directly or indirectly in the derivation of the data encryption key(s) for Low user 
  data assets. 
  ""","FCS_CKH_EXT1")
class FCS_CKH_EXT1 {

    @Rule
    @JvmField
    val adb = AdbDeviceRule()
    val client = adb.adb

    @Rule
    @JvmField
    var errs: ErrorCollector = ErrorCollector()

    //@Rule @JvmField
    //var watcher:TestWatcher = ADSRPTestWatcher(adb)
    @Rule
    @JvmField
    var name: TestName = TestName()

    //Asset Log
    var a: TestAssertLogger = TestAssertLogger(name)


    private val TEST_PACKAGE = "com.example.directboot"
    private val TEST_MODULE = "directboot-debug.apk"

    @Before
    fun setUp() {
        runBlocking {

        }
    }

    @After
    fun teardown() {
        runBlocking {

        }
    }

    @Test
    fun testDeviceEncryptedStorage() {
        runBlocking {
            //install file
            val file_apk =
                File(Paths.get(resource_path(),"FCS_CKH_EXT1", TEST_MODULE).toUri())

            val ret = AdamUtils.InstallApk(file_apk, false, adb)
            Assert.assertTrue(ret.startsWith("Success"))
            MatcherAssert.assertThat(
                a.Msg("Verify Install apk v1 (expect=Success)"),
                ret, StringStartsWith("Success")
            )

            //launch application to write a file into the storage
            //am start -a com.example.ACTION_NAME -n com.package.name/com.package.name.ActivityName
            async {
                client.execute(
                    ShellCommandRequest("am start ${TEST_PACKAGE}/${TEST_PACKAGE}.MainActivity"),
                    adb.deviceSerial
                )
            }
            var result: List<LogcatResult>? =
                AdamUtils.waitLogcatLineByTag(50, "FCS_CKH_EXT_TEST", adb)
            var matched = false;
            result?.forEach {
                if (it.text.startsWith("Booted")) {
                    matched = true;
                }
            }
            //assertThat { result }.isNotNull()
            errs.checkThat(
                a.Msg("Check The application booted.(It prepares directboot.)"),
                matched, IsEqual(true)
            );

            //(Require)Reboot Device
            //1. We expect the bootloader of the device is unlocked.
            //2. Users need to relaunch the device quickly
            client.execute(request = RebootRequest(), serial = adb.deviceSerial)
            logging("> ** Rebooting : Please Reboot Device **")
            //Note:  the connection to the adb server will be dismissed during the rebooting
            logging("> ** Maybe it requires manual operation : Please Reboot the target device as fast as possible **")
            adb.waitBoot()
            client.execute(request = KillAdbRequest())
            //client.execute(request = StartAdbRequest())

            Thread.sleep(1000*15)
            logging("> ** Reconnected")
            result = AdamUtils.waitLogcatLineByTag(200, "FCS_CKH_EXT_TEST", adb)
            if (result.isEmpty()) {
                result = listOf(LogcatResult("", "<null>"))
            }

            // Evaluates below behaviours. Application will be triggered by LOCKED_BOOT_COMPLETED action.
            // 1. Check if we can access to the DES(Device Encrypted Storage)
            // 2. Check we can not access to the CES

            matched = false;
            result?.forEach {
                if (it.text.startsWith("des=Success,ces=Failed")) {
                    matched = true;
                }
            }
            //assertThat { result }.isNotNull()
            errs.checkThat(
                a.Msg("Check if we can access to the DES/We can not accees to CES."),
                matched, IsEqual(true)
            );

        }
    }
}