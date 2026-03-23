package org.example.mutton.uidumper
import kotlinx.serialization.Serializable

@Serializable
data class Rect(
    val top: Int,
    val left: Int,
    val right: Int,
    val bottom: Int
)
@Serializable
data class UiNode(
    val index: Int,
    val text: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val contentDescription: String,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val password: Boolean,
    val selected: Boolean,
    val bounds: Rect,
    val naf: Boolean,
    val children: List<UiNode>
)