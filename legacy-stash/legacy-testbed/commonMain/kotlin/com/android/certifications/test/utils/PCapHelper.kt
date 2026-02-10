package com.android.certifications.test.utils

import com.android.certifications.test.rule.AdbDeviceRule
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.runBlocking
import logging
import org.junit.Assert
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

class PCapHelper {
    companion object{
    val PKG_PCAPDROID ="com.emanuelef.remote_capture"

   // val OUT_PATH  = "../results/capture/"
    fun copyPcapToOutPath(pcap:Path,testlabel:String):Path
    {
        val outdir = File(Paths.get(output_path(),"tlscapture").toUri())
        if(!outdir.exists()){
            outdir.mkdir()
        } else if(!outdir.isDirectory){
            outdir.delete()
            outdir.mkdir()
        }
        val tstmp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
        val to = Paths.get(outdir.path,"${tstmp}-${testlabel}.pcap")
        try {
            Files.copy(pcap, to)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return to
    }

    fun tlsCapturePacket(adb:AdbDeviceRule,testlabel:String,testurl:String):Pair<String, Path> {

        var pcap: Path = Paths.get("")
        var http_resp:String=""

        runBlocking {
            //prerequite module check
            var kCommand = "compgen -ac tshark"
            if(isWindows()){
                HostShellHelper.executeCommand("RefreshEnv.cmd")

                kCommand="where tshark"
            }
            var cmdret = HostShellHelper.executeCommand(kCommand)
            if(!cmdret.first.equals(0)){
                logging("tshark is not found. please install the command to the environment")
                Assert.assertTrue(false)
                return@runBlocking
            }

            val serial = adb.deviceSerial
            val client: AndroidDebugBridgeClient = adb.adb;

            //Install prerequisite modules
            /*val pcap_apk=
                File(Paths.get("src", "test", "resources", "pcapdroid-debug.apk").toUri())
            var ret = AdamUtils.InstallApk(pcap_apk, true,adb)
            Assert.assertTrue(ret.startsWith("Success"))
            val browser_apk=
                File(Paths.get("src", "test", "resources", "openurl-debug.apk").toUri())
            ret = AdamUtils.InstallApk(browser_apk, false,adb)
            Assert.assertTrue(ret.startsWith("Success"))*/

            val response =
                client.execute(
                    ShellCommandRequest(
                    "am start -n com.emanuelef.remote_capture/.activities.CaptureCtrl"+
                            " -e action start"+
                            " -e pcap_dump_mode pcap_file"+
                            " -e pcap_name traffic.pcap"
                ),serial)

            logging(response.output)

            //Launch packet capture software with uiautomator session
            //if it's first time we should say 'OK' to 3 dialogues,
            //after that we only need to say once.
            Thread.sleep(1000)
            UIAutomatorSession(adb,PKG_PCAPDROID).run {
                val label0= "${PKG_PCAPDROID}:id/allow_btn"
                logging("pcapdroid ui check:"+exists(label0))
                if(exists(label0)){ tap(label0) } else return@run
                Thread.sleep(2000)
                UIAutomatorSession(adb,PKG_PCAPDROID).run level2@{
                    val label1= "android:id/button1"
                    //logging(exists(label1))
                    if(exists(label1)){ tap(label1) } else return@level2
                    Thread.sleep(2000)
                    UIAutomatorSession(adb,"com.android.vpndialogs").run level3@{
                        val label2= "android:id/button1"
                        //logging(exists(label2))
                        if(exists(label2)){ tap(label2) } else return@level3
                    }
                }
            }
            Thread.sleep(3000)
            //Launch openurl app to access a certain website!
            client.execute(
                ShellCommandRequest(
                "am start -a android.intent.action.VIEW -n com.example.openurl/.MainActivity"+
                        " -e openurl $testurl"
            ),serial)

            //Wait worker response on logcat and get return code from that
            val res:List<LogcatResult> =
                //AdamUtils.waitLogcatLine(100,"worker@return",adb)
                AdamUtils.waitLogcatLineByTag(100,"worker@return",adb)
            if(!res.isEmpty()){
                logging("worker@return=>"+res.first().text)
                //evaluate the return value
            } else {
                //res == null break *panic*
                logging("we can't grab the return value from worker.")
                Assert.assertTrue(false)
            }
            //return value
            http_resp = res.first().text
            //
            Thread.sleep(500)
            //Open a connection(?) on the URL(??) and cast the response(???)
            //kill processes
            client.execute(ShellCommandRequest("am force-stop com.emanuelf.remote_capture"),serial)
            client.execute(ShellCommandRequest("am force-stop com.example.openurl"),serial)
            Thread.sleep(500)
            //pull a pdml file
            val src = "/storage/emulated/0/Download/PCAPdroid/traffic.pcap"
            val pcap0: Path = kotlin.io.path.createTempFile("t", ".pcap")
            AdamUtils.pullfile(src, pcap0.toString(), adb, true)
            //
            pcap = copyPcapToOutPath(pcap0,testlabel)
            Thread.sleep(3000)

            //convert pcap to pdml file to analyze
            val cmd="""tshark -r ${pcap.toAbsolutePath()} -o tls.debug_file:ssldebug.log -o tls.desegment_ssl_records:TRUE -o tls.desegment_ssl_application_data:TRUE -V -T pdml > ${pcap.toAbsolutePath()}.xml"""
            cmdret = HostShellHelper.executeCommand(cmd)

            logging(cmdret.second)
            Thread.sleep(1000)
            //return Pair<String,Path>(res!!.text,pcap)
        }
        return Pair(http_resp,pcap)
    }
    }
}