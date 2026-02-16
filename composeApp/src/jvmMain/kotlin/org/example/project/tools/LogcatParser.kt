package org.example.project.tools

import org.example.project.LogLevel
import org.example.project.LogLine
import java.util.regex.Pattern

object LogcatParser {

    // long形式: [ 02-13 17:02:12.123  1234: 5678 D/Tag      ]
    // グループ: 1=日時, 2=PID, 3=TID, 4=レベル, 5=タグ
    private val HEADER_PATTERN = Pattern.compile(
        "^\\s*\\[\\s+(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+):\\s*(\\d+)\\s+([VDIWEF])/(.*?)\\s*\\]\\s*$"
    )

    fun isHeader(line: String): Boolean {
        return HEADER_PATTERN.matcher(line).matches()
    }

    fun parseHeader(line: String): LogLine? {
        val matcher = HEADER_PATTERN.matcher(line)
        if (!matcher.matches()) return null

        val timestamp = matcher.group(1)
        val pid = matcher.group(2)
        val levelChar = matcher.group(4)
        val tag = matcher.group(5).trim()

        // メッセージはこの行には含まれないので空文字で初期化
        val message = ""

        val level = when (levelChar) {
            "D", "V" -> LogLevel.DEBUG
            "W" -> LogLevel.WARN
            "E", "F" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
        val pkgName = ProcessNameResolver.getPackageName(pid)

        return LogLine(timestamp, tag, message, level,pid,pkgName)
    }
}