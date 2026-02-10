package com.android.certifications.test.utils

import com.android.certifications.test.rule.AdbDeviceRule
import com.malinskiy.adam.request.shell.v2.ChanneledShellCommandRequest
import com.malinskiy.adam.request.shell.v2.ShellCommandInputChunk
import com.malinskiy.adam.request.shell.v2.ShellCommandResultChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logging

class ShellRequestThread(): Thread() {

    lateinit var stdioJob: Job
    lateinit var stdoutBuilder:StringBuilder
    lateinit var stderrBuilder:StringBuilder
    lateinit var receiveChannel_:ReceiveChannel<ShellCommandResultChunk>

    var isRunning = false;
    var exitCode = 1
    var shellCommand:String =""
    var adb:AdbDeviceRule? = null
    fun setShellCommand(shellCommand: String,adb:AdbDeviceRule){
        this.shellCommand = shellCommand
        this.adb = adb
    }
    fun isInitialized():Boolean{
        if(::stdioJob.isInitialized){
            return true
        } else {
            return false
        }
    }

    override fun run() {
        //super.run()

        if(shellCommand.equals(""))
            return

        runBlocking {
            val stdio = Channel<ShellCommandInputChunk>()
            receiveChannel_ = adb!!.adb.execute(
                ChanneledShellCommandRequest(shellCommand, stdio), this,
                adb!!.deviceSerial
            )
            //receiveChannel_=receiveChannel;
            stdioJob = launch(Dispatchers.IO) {
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
            stdoutBuilder = StringBuilder()
            stderrBuilder = StringBuilder()

           for (i in receiveChannel_) {
                //logging("${stdioJob.isActive}/${stdioJob.isCompleted}/${stdioJob.isCancelled}")
                isRunning=true;
                i.stdout?.let { stdoutBuilder.append(String(it, Charsets.UTF_8)) }
                i.stderr?.let { stderrBuilder.append(String(it, Charsets.UTF_8)) }
                i.exitCode?.let { exitCode = it }
                logging(i.stdout?.toString(Charsets.UTF_8)!!)
            }

            isRunning=false
        }
    }

    override fun interrupt() {
        super.interrupt()
        if(isRunning){
            logging("Server canceled")
            isRunning=false;
            receiveChannel_.cancel();
        }
    }

}