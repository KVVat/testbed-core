package org.example.project.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class CommandResult(val exitCode: Int, val output: String)

class FastbootClient {

    /**
     * fastbootバイナリの有効なパスを解決します。
     * 1. プロジェクトローカル
     * 2. 各OSの標準SDKパス
     * 3. システムPATH
     */
    private val executable: String by lazy {
        val osName = System.getProperty("os.name").lowercase()
        val binName = if (osName.contains("win")) "fastboot.exe" else "fastboot"
        val home = System.getProperty("user.home")

        // 1. プロジェクトローカル
        val local = File("bin/platform-tools/$binName")
        if (local.exists() && local.canExecute()) return@lazy local.absolutePath

        // 2. 各OSの標準パス
        val sdkPaths = mutableListOf<String>()
        if (osName.contains("mac")) {
            sdkPaths.add("$home/Library/Android/sdk/platform-tools/$binName")
        } else if (osName.contains("win")) {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (localAppData != null) sdkPaths.add("$localAppData\\Android\\Sdk\\platform-tools\\$binName")
        } else if (osName.contains("linux")) {
            sdkPaths.add("$home/Android/Sdk/platform-tools/$binName")
            sdkPaths.add("/usr/lib/android-sdk/platform-tools/$binName")
        }

        for (path in sdkPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) return@lazy file.absolutePath
        }

        // 3. デフォルト (PATHに期待)
        binName
    }

    private suspend fun executeCommand(vararg args: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(executable, *args)
                .redirectErrorStream(true)
                .start()

            process.waitFor(30, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()

            CommandResult(process.exitValue(), output.trim())
        } catch (e: Exception) {
            CommandResult(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun getDevices(): List<String> {
        val result = executeCommand("devices")
        if (result.exitCode == 0) {
            return result.output.lines().filter { it.isNotBlank() }.map { it.split("\\s+".toRegex()).first() }
        }
        return emptyList()
    }

    suspend fun flashPartition(serial: String, partition: String, imageFile: File): CommandResult {
        if (!imageFile.exists()) {
            return CommandResult(-1, "Image file not found: ${imageFile.absolutePath}")
        }
        return executeCommand("-s", serial, "flash", partition, imageFile.absolutePath)
    }

    suspend fun reboot(serial: String): CommandResult {
        return executeCommand("-s", serial, "reboot")
    }
}
