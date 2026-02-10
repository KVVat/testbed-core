package com.android.certifications.test

import com.android.certifications.test.rule.AdbDeviceRule
import com.android.certifications.test.utils.AdamUtils
import com.android.certifications.test.utils.LogcatResult
import com.android.certifications.test.utils.SFR
import com.android.certifications.test.utils.TestAssertLogger
import com.android.certifications.test.utils.resource_path
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.request.pkg.UninstallRemotePackageRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandResult
import kotlinx.coroutines.runBlocking
import logging
import org.hamcrest.MatcherAssert
import org.hamcrest.core.IsEqual
import org.hamcrest.core.StringStartsWith
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.rules.TestName
import java.io.File
import java.nio.file.Paths

@SFR("FDP_ACC.1 Subset Access Control", """
  The terms object and subject refer to generic elements in the TOE. For a policy to be 
  implementable, the entities must be clearly identified. For a PP, the objects and operations might
  be expressed as types such as: named objects, data repositories, observe accesses, etc. 
  For a specific system these generic terms (subject, object) must be refined, e.g. files, registers,
  ports, daemons, open calls, etc.
  This component specifies that the policy cover some well-defined set of operations on some subset 
  of the objects. It places no constraints on any operations outside the set - 
  including operations on objects for which other operations are controlled.
  """, shortname = "FDP_ACC1")
class FDP_ACC1 {

    @Rule
    @JvmField
    val adb: AdbDeviceRule = AdbDeviceRule()
    private val client: AndroidDebugBridgeClient = adb.adb;

    private val LONG_TIMEOUT = 5000L
    private val SHORT_TIMEOUT = 1000L

    private val file_module: File =
        File(Paths.get(resource_path(),"FDP_ACC1","assets-debug.apk").toUri())
    private val TEST_PACKAGE = "com.example.assets"

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
            client.execute(UninstallRemotePackageRequest(TEST_PACKAGE), adb.deviceSerial)
            AdamUtils.RemoveApk(file_module,adb)
        }
    }
    @After
    fun teardown() {
        runBlocking {
            AdamUtils.RemoveApk(file_module,adb)
        }
    }

    @Test
    fun testUserAssets()
    {
        runBlocking {

            logging("Found file to install:"+file_module.exists())
            var response: ShellCommandResult
            var result: List<LogcatResult>
            var ret = AdamUtils.InstallApk(file_module,false,adb)
            MatcherAssert.assertThat(
                a.Msg("Install Package"),
                ret, StringStartsWith("Success")
            )

            logging("Install done")

            Thread.sleep(SHORT_TIMEOUT*2)

            //launch application and prepare
            response = client.execute(ShellCommandRequest("am start -n $TEST_PACKAGE/$TEST_PACKAGE.PrepareActivity"), adb.deviceSerial)
            MatcherAssert.assertThat(
                a.Msg("Preparing Test Files with opening PrepareActivity"),
                response.output, StringStartsWith("Starting")
            )

            Thread.sleep(LONG_TIMEOUT)
            response = client.execute(ShellCommandRequest("am start -n $TEST_PACKAGE/$TEST_PACKAGE.MainActivity"), adb.deviceSerial)
            MatcherAssert.assertThat(
                a.Msg("Check file acccess via MainActivity"),
                response.output, StringStartsWith("Starting")
            )

            logging("> Test result shows the file acccess status below. 0=Preference/1=Private File/2=Media Storage/3=Database")
            result = AdamUtils.waitLogcatLineByTag(30,"FDP_ACC_1_TEST",adb)
            var matched =false;
            result.forEach{
                if(it.text.equals("Test Result:true/true/true/true")){
                    matched=true;
                }
            }
            MatcherAssert.assertThat(
                a.Msg("Check Output of the Test Package"),
                matched, IsEqual(true)
            )
            //uninstall application =>
            response = client.execute(UninstallRemotePackageRequest(TEST_PACKAGE), adb.deviceSerial)
            MatcherAssert.assertThat(
                a.Msg("Uninstall Test Package"),
                response.output, StringStartsWith("Success")
            )
            //install application => files execpt media storage will be removed,
            //The app will lost the access permission to the owner file once uninstall it.
            //so we should reinstall it with -g option to enable read_media_storage permission
            ret = AdamUtils.InstallApk(file_module,false,adb)
            MatcherAssert.assertThat(
                a.Msg("Install Package"),
                ret, StringStartsWith("Success")
            )
            Thread.sleep(SHORT_TIMEOUT*2)

            response = client.execute(ShellCommandRequest("am start -n $TEST_PACKAGE/$TEST_PACKAGE.MainActivity"), adb.deviceSerial)
            MatcherAssert.assertThat(
                a.Msg("Check file access via MainActivity"),
                response.output, StringStartsWith("Starting")
            )

            result = AdamUtils.waitLogcatLineByTag(30,"FDP_ACC_1_TEST",adb)
            matched=false
            result.forEach{
                if(it.text.equals("Test Result:false/false/true/false")){
                    matched=true;
                }
            }

            MatcherAssert.assertThat(
                a.Msg("Check Output of the Test Package"),
                matched, IsEqual(true)
            )
        }
    }
}