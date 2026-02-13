package org.example.project.tools

import org.example.project.LogLevel
import org.example.project.LogLine

object LogcatParser {
    fun parse(line: String): LogLine? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null

        val levelChar = parts[4]
        val parsedLevel = when (levelChar) {
            "D", "V" -> LogLevel.DEBUG
            "W" -> LogLevel.WARN
            "E", "F" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }

        val body = line.substringAfter(parts[4]).trim()
        val tag = body.substringBefore(":").trim()
        val message = body.substringAfter(":").trim()

        return LogLine("${parts[0]} ${parts[1]}", tag, message, parsedLevel)
    }
}