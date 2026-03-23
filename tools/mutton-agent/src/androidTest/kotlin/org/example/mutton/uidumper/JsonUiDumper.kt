package org.example.mutton.uidumper

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class JsonUiDumper {
    private val LOGTAG = "JsonUiDump"

    private val NAF_EXCLUDED_CLASSES = arrayOf(
        "android.widget.GridView",
        "android.widget.GridLayout",
        "android.widget.ListView",
        "android.widget.TableLayout"
    )

    private fun safeNull(c: CharSequence?): String = c?.toString() ?: ""

    private fun nafExcludedClass(node: AccessibilityNodeInfo): Boolean {
        val className = safeNull(node.className)
        return NAF_EXCLUDED_CLASSES.any { className.endsWith(it) }
    }

    private fun nafCheck(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable && node.isEnabled &&
                safeNull(node.contentDescription).isEmpty() &&
                safeNull(node.text).isEmpty()
    }

    fun dumpNodeRec(node: AccessibilityNodeInfo, index: Int): UiNode {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val isNaf = !nafExcludedClass(node) && !nafCheck(node)
        val children = mutableListOf<UiNode>()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isVisibleToUser) {
                    children.add(dumpNodeRec(child, i))
                } else {
                    Log.i(LOGTAG, "Skipping invisible child: $child")
                }
                // 【超重要】メモリリーク/Binderクラッシュを防ぐために必ずリサイクル
                child.recycle()
            } else {
                Log.i(LOGTAG, "Null child $i/${node.childCount}")
            }
        }

        return UiNode(
            index = index,
            text = safeNull(node.text),
            resourceId = safeNull(node.viewIdResourceName),
            className = safeNull(node.className),
            packageName = safeNull(node.packageName),
            contentDescription = safeNull(node.contentDescription),
            checkable = node.isCheckable,
            checked = node.isChecked,
            clickable = node.isClickable,
            enabled = node.isEnabled,
            focusable = node.isFocusable,
            focused = node.isFocused,
            scrollable = node.isScrollable,
            longClickable = node.isLongClickable,
            password = node.isPassword,
            selected = node.isSelected,
            bounds = Rect(bounds.top, bounds.left, bounds.right, bounds.bottom),
            naf = isNaf,
            children = children
        )
    }
}