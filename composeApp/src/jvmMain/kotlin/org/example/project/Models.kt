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

data class AppUiState(
    val isRunning: Boolean = false,
    val adbIsValid: Boolean = false,
    val isUnauthorized: Boolean = false,
    val deviceSerial: String = "",       // ★追加
    val deviceInfo: String = ""          // ★追加
)

data class TestPlugin(
    val id: String,
    val name: String,
    val clazz: Class<*>? = null, // 古いプラグイン用の後方互換性
    val className: String = "",  // ★ 追加: クラス名（文字列）
    val jarFile: java.io.File? = null, // ★ 追加: JARファイルのパス
    val shortName: String
) {
    // ★ 追加: 実行時に初めてURLClassLoaderを回してクラスを実体化するメソッド
    fun resolveClass(): Class<*> {
        if (clazz != null) return clazz // 互換性フォールバック
        val loader = java.net.URLClassLoader(arrayOf(jarFile!!.toURI().toURL()), this.javaClass.classLoader)
        return loader.loadClass(className)
    }
}