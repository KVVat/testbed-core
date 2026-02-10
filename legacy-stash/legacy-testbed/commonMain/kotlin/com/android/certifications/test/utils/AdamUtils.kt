package com.android.certifications.test.utils

import com.android.certifications.test.rule.AdbDeviceRule
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.request.Feature
import com.malinskiy.adam.request.adbd.RestartAdbdRequest
import com.malinskiy.adam.request.adbd.RootAdbdMode
import com.malinskiy.adam.request.logcat.ChanneledLogcatRequest
import com.malinskiy.adam.request.logcat.LogcatSinceFormat
import com.malinskiy.adam.request.misc.FetchHostFeaturesRequest
import com.malinskiy.adam.request.pkg.InstallRemotePackageRequest
import com.malinskiy.adam.request.prop.GetSinglePropRequest
import com.malinskiy.adam.request.shell.v2.ChanneledShellCommandRequest
import com.malinskiy.adam.request.shell.v2.ShellCommandInputChunk
import com.malinskiy.adam.request.shell.v2.ShellCommandRequest
import com.malinskiy.adam.request.shell.v2.ShellCommandResult
import com.malinskiy.adam.request.sync.v2.PullFileRequest
import com.malinskiy.adam.request.sync.v2.PushFileRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logging
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.TimeZone

class AdamUtils {
  companion object{
    fun root(adb: AdbDeviceRule):String{
      var ret:String
      runBlocking {
       ret = adb.adb.execute(
         request = RestartAdbdRequest(RootAdbdMode),
         serial = adb.deviceSerial)
      }

      logging("Restart adb=>$ret")
      return ret
    }

    fun shellRequestStream(shellCommand:String,adb: AdbDeviceRule){
      runBlocking {
        val stdio = Channel<ShellCommandInputChunk>()
        val receiveChannel = adb.adb.execute(ChanneledShellCommandRequest(shellCommand, stdio), this,
          adb.deviceSerial)
        val stdioJob = launch(Dispatchers.IO) {
          stdio.send(
            ShellCommandInputChunk(
              stdin = "".toByteArray(Charsets.UTF_8)
            )
          )
          stdio.send(
            ShellCommandInputChunk(
              close = true
            )
          )
        }
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        var exitCode = 1
        for (i in receiveChannel) {
          i.stdout?.let { stdoutBuilder.append(String(it, Charsets.UTF_8)) }
          i.stderr?.let { stderrBuilder.append(String(it, Charsets.UTF_8)) }
          i.exitCode?.let { exitCode = it }
          logging(i.stdout?.toString(Charsets.UTF_8)!!)
        }
        stdioJob.join()
        logging("Stream shellCommand done")
      }
    }
    fun shellRequest(shellCommand:String,adb: AdbDeviceRule):ShellCommandResult{
      var ret:ShellCommandResult

      runBlocking {
        //logging("Run shell command($shellCommand)")

        ret = adb.adb.execute(
          ShellCommandRequest(shellCommand),
          adb.deviceSerial)
        //logging("exitCode => ${ret.exitCode}")
        //logging("Output => ${ret.output}")
      }

      return ret
    }
    fun waitLogcatLineByTag(waitTime:Int,tagSearch:String,adb: AdbDeviceRule):List<LogcatResult> {
      var found = false
      var client = adb.adb;

      var deviceSerial = adb.deviceSerial
      val returnList:MutableList<LogcatResult>
              = mutableListOf()

      runBlocking {
        val deviceTimezoneString = client.execute(GetSinglePropRequest("persist.sys.timezone"), deviceSerial).trim()

        val deviceTimezone = TimeZone.getTimeZone(deviceTimezoneString)
        val nowInstant = Instant.now()
        //Prepare Channeled Logcat Request
        val request = ChanneledLogcatRequest(LogcatSinceFormat.DateString(nowInstant, deviceTimezoneString), modes = listOf())
        val channel = client.execute(request, this, deviceSerial)

        // Receive logcat for max several seconds, wait and find certain tag text

        for (i in 1..waitTime) {
          val lines:List<LogLine> = channel.receive()
            .split("\n")
            .mapNotNull { LogLine.of(it, deviceTimezone) }
            .filterIsInstance<LogLine.Log>()
            //.filter { it.level == 'D'}
            .filter {
              it.tag.equals(tagSearch)
            }

          if(lines.isNotEmpty()){
            //logging("batch $i");
            lines.forEach(){
              //nowInstant.epochSecond
              val epochSecond2 = it.date.time.time/1000
              logging("LogTime:$epochSecond2 CallTime ${nowInstant.epochSecond}")
              logging("$it")

              returnList.add(LogcatResult(it.tag,it.text))
              found = true
            }
          }
          delay(100)
        }
        channel.cancel()
      }
      return if(found) {
        returnList
      } else {
        emptyList()
      }
    }


    fun pullfile(sourcePath:String, dest:String, adb: AdbDeviceRule, copytoFile:Boolean=false){
      runBlocking {
        val p: Path = Paths.get(sourcePath)
        val destPath: Path = if(copytoFile){
          Paths.get(dest)
        } else {
          Paths.get(dest, p.fileName.toString())
        }

        val features: List<Feature> = adb.adb.execute(request = FetchHostFeaturesRequest())
        val channel = adb.adb.execute(
          PullFileRequest(sourcePath,destPath.toFile(),
                          supportedFeatures = features,null,coroutineContext),
          this,
          adb.deviceSerial)

        logging("Process(Pull):"+sourcePath+"=>"+destPath.toString())

        var percentage:Int
        for (percentageDouble in channel) {
          percentage = (percentageDouble * 100).toInt()
          if(percentage>=100) {
            logging("Pulling a file($sourcePath) $percentage% done")
            //percentage = 101
          }
        }
      }
    }


    fun RemoveApk(apkFile: File, adb: AdbDeviceRule):String{
      var stdio: com.malinskiy.adam.request.shell.v1.ShellCommandResult
      val client:AndroidDebugBridgeClient = adb.adb
      runBlocking {
        val fileName = apkFile.name
        stdio = client.execute(
          com.malinskiy.adam.request.shell.v1.ShellCommandRequest("rm /data/local/tmp/$fileName"),
          adb.deviceSerial)
      }
      return stdio.output
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun InstallApk(apkFile: File, reinstall: Boolean = false, adb: AdbDeviceRule): String {
      var stdio: com.malinskiy.adam.request.shell.v1.ShellCommandResult
      val client:AndroidDebugBridgeClient = adb.adb

      runBlocking {
        val features: List<Feature> = adb.adb.execute(request = FetchHostFeaturesRequest())
        val fileName = apkFile.name
        val channel = client.execute(PushFileRequest(apkFile, "/data/local/tmp/$fileName",features),
                                     GlobalScope,
                                     serial = adb.deviceSerial)
        var done = false
        while (!channel.isClosedForReceive) {
          val progress: Double? =
            channel.tryReceive().onClosed {
              //Thread.sleep(1)
              delay(0)
            }.getOrNull()

          if(progress!==null && progress==1.0 && done==false) {
            done = true
            logging("Install $fileName completed")
          }
        }
        //add -g option to permit all exisitng runtime option
        stdio = client.execute(InstallRemotePackageRequest(
          "/data/local/tmp/$fileName", reinstall, listOf("-g")), serial = adb.deviceSerial)
      }
      logging(stdio.output)
      return stdio.output
    }
  }
}



