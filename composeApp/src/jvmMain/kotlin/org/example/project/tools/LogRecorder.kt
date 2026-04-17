package org.example.project.tools

import org.example.project.LogLine
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogRecorder(
    private val baseFileName: String = "logcat_output.log",
    private val maxFileSizeMb: Long = 10, // 10MBごとにローテーション
    private val maxBackupIndex: Int = 5   // 最大5世代まで保持
) {
    private val file = File(baseFileName)
    private var writer: PrintWriter? = null

    // 書き込みフォーマット (読みやすい1行形式に整形)
    // 例: 2024-02-14 12:00:00.123 1234/com.app.example D/Tag: Message...
    private fun formatLogLine(log: LogLine): String {
        val pidInfo = if (!log.pid.isNullOrEmpty()) {
            if (log.packageName != null) "${log.pid}/${log.packageName}" else log.pid
        } else ""
        val sep = if (pidInfo.isNotEmpty()) " $pidInfo" else ""
        return "${log.timestamp}$sep ${log.level.name.first()}/${log.tag}: ${log.message}"
    }

    @Synchronized
    fun write(line: String) {
        // ファイルサイズチェック & ローテーション
        if (file.exists() && file.length() > maxFileSizeMb * 1024 * 1024) {
            rotate()
        }

        // Writerが開いていなければ開く
        if (writer == null) {
            writer = PrintWriter(FileOutputStream(file, true), true)
        }

        writer?.println(line)
    }

    fun write(log: LogLine) {
        write(formatLogLine(log))
    }

    private fun rotate() {
        writer?.close()
        writer = null

        // 古いログを後ろにずらす (log.4 -> log.5, log.3 -> log.4 ...)
        val lastFile = File("$baseFileName.$maxBackupIndex")
        if (lastFile.exists()) lastFile.delete()

        for (i in maxBackupIndex - 1 downTo 1) {
            val src = File("$baseFileName.$i")
            val dst = File("$baseFileName.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }

        // 現在のログを log.1 にリネーム
        val current = File(baseFileName)
        val backup = File("$baseFileName.1")
        if (current.exists()) current.renameTo(backup)

        // 次回の書き込み時に新しい baseFileName が作成される
    }

    fun close() {
        writer?.close()
        writer = null
    }
}