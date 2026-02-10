package com.android.certifications.test

import com.android.certifications.test.rule.AdbDeviceRule
import com.android.certifications.test.utils.AdamUtils
import com.android.certifications.test.utils.SFR
import com.android.certifications.test.utils.TestAssertLogger
import com.android.certifications.test.utils.resource_path
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.request.pkg.UninstallRemotePackageRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandResult
import kotlinx.coroutines.runBlocking
import logging
import org.hamcrest.CoreMatchers
import org.hamcrest.core.IsEqual
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.rules.TestName
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

@SFR("FPR_PSE.1 Pseudonymity", """
 Pseudonymity requires that a set of users and/or subjects are
 unable to determine the identity of a user bound to a subject or operation, but
 that this user is still responsible for its actions.
  """, shortname = "FPR_PSE1")
class FPR_PSE1 {
    @Rule
    @JvmField
    val adb: AdbDeviceRule = AdbDeviceRule()
    private val client: AndroidDebugBridgeClient = adb.adb;

    @Rule @JvmField
    var errs: ErrorCollector = ErrorCollector()
    @Rule @JvmField
    var name: TestName = TestName()
    //Asset Log
    var a: TestAssertLogger = TestAssertLogger(name)

    val file_module: File =
        File(Paths.get(resource_path(),"FPR_PSE1", "uniqueid-debug.apk").toUri())

    private val TEST_PACKAGE = "com.example.uniqueid"

    private val LONG_TIMEOUT = 5000L
    private val SHORT_TIMEOUT = 1000L


    @Before
    fun setUp()
    {
        runBlocking {
            AdamUtils.RemoveApk(file_module,adb);
        }
    }
    @After
    fun teardown() {
        runBlocking {
            AdamUtils.RemoveApk(file_module,adb);
        }
    }

    @Test
    fun testUniqueIDs()
    {
        runBlocking {

            logging("> The test verifies that the apis which generate unique ids return expected values.")
            AdamUtils.InstallApk(file_module, true,adb)

            Thread.sleep(SHORT_TIMEOUT*2)
            //launch application (am start -n com.package.name/com.package.name.ActivityName)
            var response :ShellCommandResult

            response= client.execute(ShellCommandRequest("am start -n $TEST_PACKAGE/$TEST_PACKAGE.MainActivity"), adb.deviceSerial)
            logging("Call activity results : ${response.output}")
            Thread.sleep(LONG_TIMEOUT)
            response =
                client.execute(ShellCommandRequest("run-as ${TEST_PACKAGE} cat /data/data/$TEST_PACKAGE/shared_prefs/UniqueID.xml"), adb.deviceSerial)
            //store preference into map A
            //the map contains unique ids below : ADID,UUID,AID,WIDEVINE (see application code)
            val dictA:Map<String,String> = fromPrefMapListToDictionary(response.output.trimIndent())
            //
            logging("Values of each api results : "+dictA.toString())

            //kill process (am force-stop com.package.name)
            client.execute(ShellCommandRequest("am force-stop $TEST_PACKAGE"), adb.deviceSerial)

            //launch application
            client.execute(ShellCommandRequest("am start -n $TEST_PACKAGE/$TEST_PACKAGE.MainActivity"), adb.deviceSerial)
            //Thread.sleep(SHORT_TIMEOUT*15)

            //Store preference into map B/check prefernce and compare included values against A
            response =
                client.execute(ShellCommandRequest("run-as ${TEST_PACKAGE} cat /data/data/$TEST_PACKAGE/shared_prefs/UniqueID.xml"), adb.deviceSerial)
            Thread.sleep(SHORT_TIMEOUT*5)

            val dictB:Map<String,String> = fromPrefMapListToDictionary(response.output.trimIndent())
            logging("Values of each api results (after reboot) : "+dictB.toString())
            logging("Check all api values are maintained.")

            //Expected : All unique id values should be maintained
            //Note : Each test should not interrupt execution of the test case
            logging(dictB.toString())
            errs.checkThat(a.Msg("Verify UUID same"),dictA["UUID"], IsEqual(dictB["UUID"]))
            errs.checkThat(a.Msg("Verify ADID same"),dictA["ADID"], IsEqual(dictB["ADID"]))
            errs.checkThat(a.Msg("Verify AID same"),dictA["AID"], IsEqual(dictB["AID"]))
            errs.checkThat(a.Msg("Verify WIDEVINE same"),dictA["WIDEVINE"],
                IsEqual(dictB["WIDEVINE"])
            )
            errs.checkThat(a.Msg("Verify IMEI1 is blank"),dictA["IMEI1"], IsEqual(""))
            errs.checkThat(a.Msg("Verify IMEI2 is blank"),dictA["IMEI2"], IsEqual(""))
            //errs.checkThat(a.Msg("Verify DeviceSerial is blank"),dictA["DeviceSerial"],IsEqual(""))

            logging(">Uninstall/Install again the target apk.")
            //uninstall application =>
            client.execute(UninstallRemotePackageRequest(TEST_PACKAGE), adb.deviceSerial)
            Thread.sleep(SHORT_TIMEOUT*5)
            //install application again
            AdamUtils.InstallApk(file_module, false,adb)
            Thread.sleep(SHORT_TIMEOUT*5)
            //logging(respstring)
            //launch application
            client.execute(ShellCommandRequest("am start -n $TEST_PACKAGE/$TEST_PACKAGE.MainActivity"), adb.deviceSerial)
            Thread.sleep(SHORT_TIMEOUT*10)
            //check preference and compare included values against A and B
            response =
                client.execute(ShellCommandRequest("run-as ${TEST_PACKAGE} cat /data/data/$TEST_PACKAGE/shared_prefs/UniqueID.xml"), adb.deviceSerial)

            val dictC:Map<String,String> = fromPrefMapListToDictionary(response.output.trimIndent())

            logging(">Check the api values except UUID should be maintained.")
            //Expected : UUID should be changed. Others should be maintained
            //You should set allowbackup option in module's androidmanifest.xml to false
            //for passing this test.(the option makes application a bit vulnerable to attack)
            //Note : Each test should not interrupt execution of the test case
            errs.checkThat(a.Msg("Verify UUID changes"),dictA["UUID"],
                CoreMatchers.`is`(CoreMatchers.not(dictC["UUID"]))
            )
            errs.checkThat(a.Msg("Verify ADID same"),dictA["ADID"], IsEqual(dictC["ADID"]))
            errs.checkThat(a.Msg("Verify AID same"),dictA["AID"], IsEqual(dictC["AID"]))
            errs.checkThat(a.Msg("Verify WIDEVINE same"),dictA["WIDEVINE"],
                IsEqual(dictC["WIDEVINE"])
            )
            errs.checkThat(a.Msg("Verify IMEI1 is blank"),dictA["IMEI1"], IsEqual(""))
            errs.checkThat(a.Msg("Verify IMEI2 is blank"),dictA["IMEI2"], IsEqual(""))
            //errs.checkThat(a.Msg("Verify DeviceSerial is blank"),dictA["DeviceSerial"],IsEqual(""))

        }
    }

    fun fromPrefMapListToDictionary(xml:String):Map<String,String>{
        logging(xml)
        val source = InputSource(StringReader(xml))

        val dbf: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
        val db: DocumentBuilder = dbf.newDocumentBuilder()
        val document: Document = db.parse(source)

        val nodes: NodeList = document.getElementsByTagName("string")
        val  ret = mutableMapOf<String,String>()
        for(i in 0 .. nodes.length-1){
            val node: Node = nodes.item(i)
            val key:String = node.attributes.getNamedItem("name").nodeValue
            val value:String = node.textContent
            ret[key] = value
        }
        return ret
    }
}