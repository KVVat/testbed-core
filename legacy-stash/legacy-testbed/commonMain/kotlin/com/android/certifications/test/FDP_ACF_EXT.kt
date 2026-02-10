package com.android.certifications.test

import com.android.certifications.test.rule.AdbDeviceRule
import com.android.certifications.test.utils.AdamUtils
import com.android.certifications.test.utils.SFR
import com.android.certifications.test.utils.TestAssertLogger
import com.android.certifications.test.utils.resource_path
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.request.pkg.UninstallRemotePackageRequest
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert
import org.hamcrest.core.StringStartsWith
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.rules.TestName
import java.io.File
import java.nio.file.Paths
import logging


@SFR("FDP_ACF_EXT.1 App Update", """
  FDP_ACF.1 Security attribute based access control allows the TSF to enforce access based upon 
  security attributes and named groups of attributes. Furthermore, the TSF may have the ability to
  explicitly authorize or deny access to an object based upon security attributes.
  ""","FDP_ACF_EXT")
class FDP_ACF_EXT {

    @Rule
    @JvmField
    val adb: AdbDeviceRule = AdbDeviceRule()
    private val client: AndroidDebugBridgeClient = adb.adb;

    val file_apk_v1_debug: File =
        File(Paths.get(resource_path(),"FCS_ACF_EXT", "appupdate-v1-debug.apk").toUri())
    val file_apk_v2_signed: File =
        File(Paths.get(resource_path(),"FCS_ACF_EXT", "appupdate-v2-signed.apk").toUri())
    val file_apk_v2_debug: File =
        File(Paths.get(resource_path(),"FCS_ACF_EXT", "appupdate-v2-debug.apk").toUri())

    @Rule @JvmField
    var errs: ErrorCollector = ErrorCollector()
    @Rule @JvmField
    var name: TestName = TestName()
    //Asset Log
    var a: TestAssertLogger = TestAssertLogger(name)

    @Before
    fun setUp()
    {
        runBlocking {
            client.execute(UninstallRemotePackageRequest("com.example.appupdate"), adb.deviceSerial)
            AdamUtils.RemoveApk(file_apk_v1_debug,adb);
            AdamUtils.RemoveApk(file_apk_v2_debug,adb);
            AdamUtils.RemoveApk(file_apk_v2_signed,adb);
        }
    }
    @After
    fun teardown() {
        runBlocking {
            client.execute(UninstallRemotePackageRequest("com.example.appupdate"), adb.deviceSerial)
            AdamUtils.RemoveApk(file_apk_v1_debug,adb);
            AdamUtils.RemoveApk(file_apk_v2_debug,adb);
            AdamUtils.RemoveApk(file_apk_v2_signed,adb);
        }
    }

    @Test
    fun testNormalUpdate() {
        //A test for FDP_ACF_EXT.1/AppUpdate
        //UserDataProtectionTest.accessControlExt1_appUpdate_TestNormal

        logging("> The test verifies apk upgrade operation works correctly.")
        runBlocking {
            //
            var ret = AdamUtils.InstallApk(file_apk_v1_debug,false,adb)
            MatcherAssert.assertThat(
                a.Msg("Verify Install apk v1 (expect=Success)"),
                ret, StringStartsWith("Success")
            )

            ret =  AdamUtils.InstallApk(file_apk_v2_debug,false,adb)
            MatcherAssert.assertThat(
                a.Msg("Verify Install upgraded apk v2 (expect=Success)"),
                ret, StringStartsWith("Success")
            )

            //degrade
            ret = AdamUtils.InstallApk(file_apk_v1_debug,false,adb)
            MatcherAssert.assertThat(
                a.Msg("Verify Install degraded apk v1 (expect=Failure)"),
                ret, StringStartsWith("Failure")
            )

            //unistall the test file before next test
            client.execute(UninstallRemotePackageRequest("com.example.appupdate"), adb.deviceSerial)
        }
    }

    //@TestInformation(SFR="FDP_ACF_EXT.1/AppUpadate")
    @Test
    fun testAbnormalUpdate() {
        logging("> The test verifies apk upgrade fails if the signing keys are not-identical.")

        runBlocking {
            //
            println("Verify Install apk v1 (expect=Success)")
            var ret = AdamUtils.InstallApk(file_apk_v1_debug,false,adb)
            MatcherAssert.assertThat(
                a.Msg("Verify Install apk v1 (expect=Success)"),
                ret, StringStartsWith("Success")
            )

            //Signature mismatch case
            logging("Verify Install apk v2 with different signing key (expect=Failure)a")
            ret = AdamUtils.InstallApk(file_apk_v2_signed,false,adb)
            MatcherAssert.assertThat(
                a.Msg("Verify Install apk v2 with different signing key (expect=Failure)"),
                ret, StringStartsWith("Failure")
            )
            //unistall the test file before next test
            client.execute(UninstallRemotePackageRequest("com.example.appupdate"), adb.deviceSerial)
        }
    }
}