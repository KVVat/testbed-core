package org.example.project.model

import com.google.gson.annotations.SerializedName

/**
 * Android 画面内の UI ノード（Class Name, Resources, Bounds など）を格納するデータクラス。
 */
data class UiNode(
    val index: Int = 0,
    val text: String = "",
    val resourceId: String = "",
    val className: String = "",
    val packageName: String = "",
    val contentDescription: String = "",
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val clickable: Boolean = false,
    val enabled: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val longClickable: Boolean = false,
    val password: Boolean = false,
    val selected: Boolean = false,
    val bounds: UiBounds = UiBounds(0, 0, 0, 0),
    val naf: Boolean = false,
    val children: List<UiNode> = emptyList()
)

data class UiBounds(
    val top: Int,
    val left: Int,
    val right: Int,
    val bottom: Int
)

/**
 * "/dump" コマンドからのレスポンス形式
 */
data class DumpResult(
    val type: String,
    val status: String,
    val output: String, // ここにはエスケープされた JSON 文字列（UiNodeツリー）が入る
    val screenshot: String? = null, // Base64 encoded JPEG screenshot
    val screen_width: Int = 1080,
    val screen_height: Int = 2400
)
