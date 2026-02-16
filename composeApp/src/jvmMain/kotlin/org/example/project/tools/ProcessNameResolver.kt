package org.example.project.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object ProcessNameResolver {
    // スレッドセーフなマップ (PID -> PackageName)
    private val processMap = ConcurrentHashMap<String, String>()

    // ActivityManagerの起動ログ
    // 例: "Start proc 1234:com.example.app/u0a123 for activity ..."
    private val START_PROC_PATTERN = Pattern.compile(
        "Start proc (\\d+):([^/]+)/"
    )

    // プロセス終了ログ (オプション: マップが肥大化しないように消す場合)
    // 例: "Process com.example.app (pid 1234) has died: ..."
    private val DIED_PROC_PATTERN = Pattern.compile(
        "Process ([^ ]+) \\(pid (\\d+)\\) has died"
    )

    fun updateFromLog(tag: String, message: String) {
        // ActivityManagerタグのみ監視
        if (tag == "ActivityManager") {
            // 起動監視
            val startMatcher = START_PROC_PATTERN.matcher(message)
            if (startMatcher.find()) {
                val pid = startMatcher.group(1)
                val packageName = startMatcher.group(2)
                processMap[pid] = packageName
                // println("Detected Process Start: $pid -> $packageName") // デバッグ用
            }

            // 終了監視 (必要なら有効化)
            /*
            val diedMatcher = DIED_PROC_PATTERN.matcher(message)
            if (diedMatcher.find()) {
                val pid = diedMatcher.group(2)
                processMap.remove(pid)
            }
            */
        }
    }

    fun getPackageName(pid: String): String? {
        return processMap[pid]
    }

    // 初回に ps コマンドの結果を流し込む用
    fun updateBulk(pidNameMap: Map<String, String>) {
        processMap.putAll(pidNameMap)
    }

    fun clear() {
        processMap.clear()
    }
}