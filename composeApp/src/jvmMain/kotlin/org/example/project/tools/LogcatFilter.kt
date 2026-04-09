package org.example.project.tools

import org.example.project.LogLevel
import org.example.project.LogLine

/**
 * Logcatフィルタのパースとマッチングを行うユーティリティ。
 *
 * 検索ボックスの構文:
 *   - 通常テキスト → tag, message に対する部分一致 (従来通り)
 *   - (process) → プロセスフィルタ (PID完全一致 or パッケージ名部分一致)
 *   - 複数の () はOR条件 (いずれかにマッチすればOK)
 *
 * 例:
 *   "crash (com.android.settings)"  → テキスト "crash" × プロセス "com.android.settings"
 *   "(com.example) (com.other)"     → 2つのプロセスのOR指定
 *   "(1234)"                        → PID 1234 でフィルタ
 */
data class ParsedFilter(
    val textQuery: String,             // ()以外の部分（テキストフィルタ）
    val processQueries: List<String>   // ()内の部分（プロセスフィルタ）
)

object LogcatFilter {
    // "(xxx)" パターンを抽出
    private val PROCESS_PATTERN = Regex("\\(([^)]+)\\)")

    /**
     * フィルタ文字列をパースし、テキストクエリとプロセスクエリに分離する。
     */
    fun parse(filterText: String): ParsedFilter {
        val processQueries = PROCESS_PATTERN.findAll(filterText)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val textQuery = PROCESS_PATTERN.replace(filterText, "").trim()
        return ParsedFilter(textQuery, processQueries)
    }

    /**
     * LogLine が ParsedFilter と選択されたレベルに合致するかを判定する。
     */
    fun matches(log: LogLine, parsed: ParsedFilter, selectedLevels: List<LogLevel>): Boolean {
        // 1. レベルフィルタ
        if (!selectedLevels.contains(log.level)) return false

        // 2. テキストフィルタ（tag, message への部分一致）
        if (parsed.textQuery.isNotBlank()) {
            val textMatches = log.message.contains(parsed.textQuery, ignoreCase = true) ||
                    log.tag.contains(parsed.textQuery, ignoreCase = true)
            if (!textMatches) return false
        }

        // 3. プロセスフィルタ（OR条件: いずれかにマッチすればOK）
        if (parsed.processQueries.isNotEmpty()) {
            val processMatches = parsed.processQueries.any { query ->
                // PID完全一致
                log.pid == query ||
                // パッケージ名部分一致
                (log.packageName?.contains(query, ignoreCase = true) == true)
            }
            if (!processMatches) return false
        }

        return true
    }
}
