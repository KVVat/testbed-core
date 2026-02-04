package org.example.project.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// fastbootコマンドの実行結果を保持するデータクラス
data class CommandResult(val exitCode: Int, val output: String)

class FastbootClient {

    private val executable: String = "fastboot"

    // ProcessBuilderを使ってコマンドを実行する共通関数
    private suspend fun executeCommand(vararg args: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(executable, *args)
                .redirectErrorStream(true)
                .start()

            // タイムアウトを設けてプロセス終了を待つ
            process.waitFor(30, TimeUnit.SECONDS)

            // 標準出力を読み取る
            val output = process.inputStream.bufferedReader().readText()

            CommandResult(process.exitValue(), output.trim())
        } catch (e: Exception) {
            CommandResult(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * fastbootモードのデバイスリストを取得します。
     * @return 検出されたデバイスのシリアル番号リスト
     */
    suspend fun getDevices(): List<String> {
        val result = executeCommand("devices")
        if (result.exitCode == 0) {
            // "serial_number    fastboot" の形式からシリアル番号のみを抽出
            return result.output.lines().filter { it.isNotBlank() }.map { it.split("\\s+".toRegex()).first() }
        }
        return emptyList()
    }

    /**
     * 指定されたパーティションにイメージファイルを書き込みます。
     * @param serial デバイスのシリアル番号
     * @param partition 書き込むパーティション名 (例: "boot")
     * @param imageFile 書き込むイメージファイル
     * @return コマンドの実行結果
     */
    suspend fun flashPartition(serial: String, partition: String, imageFile: File): CommandResult {
        if (!imageFile.exists()) {
            return CommandResult(-1, "Image file not found: ${imageFile.absolutePath}")
        }
        return executeCommand("-s", serial, "flash", partition, imageFile.absolutePath)
    }

    /**
     * fastbootモードのデバイスを再起動します。
     * @param serial デバイスのシリアル番号
     * @return コマンドの実行結果
     */
    suspend fun reboot(serial: String): CommandResult {
        return executeCommand("-s", serial, "reboot")
    }
}