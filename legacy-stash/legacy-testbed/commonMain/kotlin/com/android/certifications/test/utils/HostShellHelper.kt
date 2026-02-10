package com.android.certifications.test.utils

import logging
import java.util.concurrent.TimeUnit
//https://stackoverflow.com/questions/57123836/kotlin-native-execute-command-and-get-the-output
//https://github.com/hoffipublic/multiplatform_cli/blob/master/lib/src/jvmMain/kotlin/com/hoffi/mppcli/lib/common/io/mpp/Process.kt

class HostShellHelper {
    companion object {


        fun executeCommand(
            command: String,
            teeStdout: Boolean=true,
            echoCmdToErr: Boolean=true
        ): Pair<Int,String> {
            if (echoCmdToErr) logging("$command")
            val outputLines = mutableListOf<String>()
            var ret:String = ""


            var kShell = "/bin/bash"
            var kShellOption = "-c"

            if(isWindows()){
                kShell="cmd.exe"
                kShellOption = "/c"
            }

            runCatching {
                val process = ProcessBuilder(kShell, kShellOption, command)
                    //.directory(workingDir)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .start()
                val bufferedReader = process.inputStream.bufferedReader()
                var line= bufferedReader.readLine()
                while (line != null) {
                    if (teeStdout) {
                        //Console.echo(line)
                        logging(line)

                    }
                    outputLines.add(line)
                    line = bufferedReader.readLine()
                }
                process.apply { waitFor(5L, TimeUnit.SECONDS) }
                ret = outputLines.joinToString(PlatformUtils.LINE_SEPARATOR)
                logging(""+process.exitValue())
                return Pair(process.exitValue(), ret)
            }.onFailure { it.printStackTrace() ; return Pair(126, ret) }
            return Pair(126, ret)
        }

        /*private fun readOutputAndWait(p:Process):String{
            val ins: InputStream = p.getInputStream() //標準出力
            val br = BufferedReader(InputStreamReader(ins))
            val sb = StringBuilder()
            try {
                while (true) {
                    val line = br.readLine() ?: break
                    sb.append(line);sb.append(PlatformUtils.LINE_SEPARATOR)
                    println(line)
                }
            } finally {
                br.close()
            }


            return sb.toString();
            //it.stringList.joinToString(PlatformUtils.LINE_SEPARATOR)
        }

        @Throws(IOException::class)
        fun executeCommands(script: String):Pair<Int,String> {
            val tempScript = createTempScript(script)
            try {
                val pb = ProcessBuilder(PlatformUtils.SHELLCMD, tempScript.toString())
                pb.inheritIO()
                val process = pb.start()
                val result = readOutputAndWait(process);
                process.waitFor(10,TimeUnit.SECONDS)
                return Pair(process.exitValue(),result)
            } finally {
                //tempScript.delete()
            }
        }

        @Throws(IOException::class)
        fun createTempScript(script:String): File {
            val tempScript = File.createTempFile("script", null)
            val streamWriter: Writer = OutputStreamWriter(
                FileOutputStream(
                    tempScript
                )
            )
            val printWriter = PrintWriter(streamWriter)
            printWriter.println(PlatformUtils.SHELLCMDPREFIX)
            printWriter.println(script)
            printWriter.close()
            return tempScript
        }*/
    }
}