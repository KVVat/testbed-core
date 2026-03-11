package org.example.project

enum class TestLogLevel {
    DEBUG, INFO, PASS, WARN, ERROR
}

object JUnitBridge {
    // StringとLevelをセットで受け取る関数に変更
    var logging: ((String, TestLogLevel) -> Unit)? = null

    // 進捗報告用のコールバック
    var onProgress: ((String, Int) -> Unit)? = null

    fun reportProgress(step: String, percent: Int) {
        onProgress?.invoke(step, percent)
    }

    // パス情報
    var resourceDir: String = ""
    var configFilePath: String = ""
}