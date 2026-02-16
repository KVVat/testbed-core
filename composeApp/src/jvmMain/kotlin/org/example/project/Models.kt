// Models.kt
package org.example.project

// ログのデータモデル
data class LogLine(
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: LogLevel,
    val pid: String = "", // ★追加
    val packageName: String? = null
)

enum class LogLevel {
    INFO, DEBUG, ERROR, PASS,WARN
}

// ついでにこれもここに移動しておくとスッキリします
data class AppUiState(
    val isRunning: Boolean = false,
    val adbIsValid: Boolean = false
)