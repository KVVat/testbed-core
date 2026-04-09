package org.example.project.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Agentから返されるUI DumpのJSONレスポンスを、LLM向けのフラット要約テキストに変換する。
 */
object UiDumpSummarizer {

    private const val MAX_LABEL_LENGTH = 50

    /**
     * 全ての「意味のある」ノードを含むサマリーを生成する。
     * テキスト/contentDescriptionがあるノード、またはclickable/scrollable等のノードが対象。
     * get_ui_dump の format="summary" で使用。
     */
    fun summarize(agentResponseJson: String): String {
        return buildSummary(agentResponseJson, ::isMeaningfulNode)
    }

    /**
     * インタラクト可能なノードのみのサマリーを生成する。
     * clickable/checkable/scrollable/longClickableなノードが対象。
     * Action tool (tap, swipe等) のレスポンスで使用。
     */
    fun summarizeInteractable(agentResponseJson: String): String {
        return buildSummary(agentResponseJson, ::isInteractableNode)
    }

    private fun buildSummary(agentResponseJson: String, filter: (JsonObject) -> Boolean): String {
        val root: JsonObject
        try {
            root = JsonParser.parseString(agentResponseJson).asJsonObject
        } catch (e: Exception) {
            return agentResponseJson // パース失敗時はそのまま返す
        }

        val status = root.get("status")?.asString
        if (status != "ok") return agentResponseJson // エラーレスポンスはパススルー

        val outputStr = root.get("output")?.asString ?: return agentResponseJson
        val screenWidth = root.get("screen_width")?.asInt ?: 0
        val screenHeight = root.get("screen_height")?.asInt ?: 0

        val uiTree: JsonObject
        try {
            uiTree = JsonParser.parseString(outputStr).asJsonObject
        } catch (e: Exception) {
            return agentResponseJson
        }

        val packageName = uiTree.get("packageName")?.asString ?: ""

        val result = WalkResult()
        walk(uiTree, result, filter)

        val sb = StringBuilder()
        sb.appendLine("Screen: ${screenWidth}x${screenHeight} | App: $packageName")
        sb.appendLine("───────────────────────────────────────────────")
        if (result.lines.isEmpty()) {
            sb.appendLine("(no interactable elements — screen may be transitioning, call get_ui_dump to retry)")
        } else {
            for (line in result.lines) {
                sb.appendLine(line)
            }
        }
        if (result.scrollableContainers.isNotEmpty()) {
            val containers = result.scrollableContainers.distinct().joinToString(", ")
            sb.appendLine("──────────────────── scrollable: $containers ─")
        }

        return sb.toString().trimEnd()
    }

    private data class WalkResult(
        val lines: MutableList<String> = mutableListOf(),
        val scrollableContainers: MutableList<String> = mutableListOf(),
        var index: Int = 0
    )

    private fun walk(node: JsonObject, result: WalkResult, filter: (JsonObject) -> Boolean) {
        val scrollable = node.get("scrollable")?.asBoolean ?: false
        if (scrollable) {
            val cls = (node.get("className")?.asString ?: "").substringAfterLast(".")
            if (cls.isNotEmpty()) result.scrollableContainers.add(cls)
        }

        if (filter(node)) {
            result.lines.add(formatNode(node, result.index))
            result.index++
        }

        val children = node.get("children")
        if (children != null && children.isJsonArray) {
            for (child in children.asJsonArray) {
                if (child.isJsonObject) {
                    walk(child.asJsonObject, result, filter)
                }
            }
        }
    }

    private fun isMeaningfulNode(node: JsonObject): Boolean {
        val text = node.get("text")?.asString ?: ""
        val desc = node.get("contentDescription")?.asString ?: ""
        val clickable = node.get("clickable")?.asBoolean ?: false
        val scrollable = node.get("scrollable")?.asBoolean ?: false
        val checkable = node.get("checkable")?.asBoolean ?: false
        val longClickable = node.get("longClickable")?.asBoolean ?: false

        return text.isNotEmpty() || desc.isNotEmpty() || clickable || scrollable || checkable || longClickable
    }

    private fun isInteractableNode(node: JsonObject): Boolean {
        val clickable = node.get("clickable")?.asBoolean ?: false
        val checkable = node.get("checkable")?.asBoolean ?: false
        val scrollable = node.get("scrollable")?.asBoolean ?: false
        val longClickable = node.get("longClickable")?.asBoolean ?: false

        return clickable || checkable || scrollable || longClickable
    }

    private fun formatNode(node: JsonObject, index: Int): String {
        val text = node.get("text")?.asString ?: ""
        val desc = node.get("contentDescription")?.asString ?: ""
        val resId = node.get("resourceId")?.asString ?: ""
        val className = (node.get("className")?.asString ?: "").substringAfterLast(".")
        val clickable = node.get("clickable")?.asBoolean ?: false
        val longClickable = node.get("longClickable")?.asBoolean ?: false
        val checkable = node.get("checkable")?.asBoolean ?: false
        val checked = node.get("checked")?.asBoolean ?: false
        val scrollable = node.get("scrollable")?.asBoolean ?: false
        val selected = node.get("selected")?.asBoolean ?: false
        val focused = node.get("focused")?.asBoolean ?: false
        val password = node.get("password")?.asBoolean ?: false

        val label = when {
            text.isNotEmpty() -> truncate(text)
            desc.isNotEmpty() -> truncate(desc)
            resId.isNotEmpty() -> resId.substringAfterLast("/")
            else -> ""
        }

        val bounds = node.get("bounds")
        val cx: Int
        val cy: Int
        if (bounds != null && bounds.isJsonObject) {
            val b = bounds.asJsonObject
            val left = b.get("left")?.asInt ?: 0
            val right = b.get("right")?.asInt ?: 0
            val top = b.get("top")?.asInt ?: 0
            val bottom = b.get("bottom")?.asInt ?: 0
            cx = (left + right) / 2
            cy = (top + bottom) / 2
        } else {
            cx = 0
            cy = 0
        }

        val isInteractable = clickable || longClickable || checkable
        val action = if (isInteractable) "tap($cx,$cy)" else "at($cx,$cy)"

        val flags = buildList {
            if (clickable) add("clickable")
            if (longClickable) add("longClickable")
            if (scrollable) add("scrollable")
            if (checkable) add(if (checked) "checked=true ★" else "checked=false")
            if (selected) add("selected")
            if (focused) add("focused")
            if (password) add("password")
        }.joinToString(" ")

        val labelPart = if (label.isNotEmpty()) "\"$label\" " else ""
        return "[$index] $className ${labelPart}$action $flags".trimEnd()
    }

    private fun truncate(s: String): String {
        return if (s.length > MAX_LABEL_LENGTH) s.take(MAX_LABEL_LENGTH) + "…" else s
    }
}
