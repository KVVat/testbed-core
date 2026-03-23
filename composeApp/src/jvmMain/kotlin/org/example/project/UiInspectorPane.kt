package org.example.project

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.model.UiNode

@Composable
fun UiInspectorPane(rootNode: UiNode?, screenshot: ImageBitmap? = null) {
    var selectedNode by remember { mutableStateOf<UiNode?>(null) }
    
    // Auto-select root when it loads and reset selection if root changes
    LaunchedEffect(rootNode) {
        selectedNode = rootNode
    }

    if (rootNode == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22)), contentAlignment = Alignment.Center) {
            Text("No UI Dump available. Click 'Dump UI Tree' to load.", color = Color.Gray)
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {
        // Left Pane: Tree View AND Details View
        Column(modifier = Modifier.weight(0.5f).fillMaxHeight().border(1.dp, Color(0xFF3C3F41))) {
            Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                val listState = rememberLazyListState()
                
                // Flatten the tree for LazyColumn rendering
                val flatNodes = remember(rootNode) { flattenTree(rootNode) }
                // Expanded states: initially, only expand root
                val expandedStates = remember { mutableStateMapOf<UiNode, Boolean>(rootNode to true) }

                // Auto-expand parents and scroll to selected node
                LaunchedEffect(selectedNode) {
                    selectedNode?.let { node ->
                        var currentOwner = flatNodes.find { it.node == node }?.parent
                        while (currentOwner != null) {
                            expandedStates[currentOwner] = true
                            currentOwner = flatNodes.find { it.node == currentOwner }?.parent
                        }

                        // Scroll to the selected node after ensuring it's expanded
                        // We need to find its index in the *visible* nodes, not flatNodes
                        val visibleNodes = flatNodes.filter { isVisible(it, flatNodes, expandedStates) }
                        val selectedIndex = visibleNodes.indexOfFirst { it.node == node }
                        if (selectedIndex >= 0) {
                            listState.animateScrollToItem(selectedIndex)
                        }
                    }
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // Filter flatNodes to only show visible items
                    val visibleNodes = flatNodes.filter { isVisible(it, flatNodes, expandedStates) }
                    
                    items(visibleNodes) { nodeData ->
                        val node = nodeData.node
                        val depth = nodeData.depth
                        val isExpanded = expandedStates[node] == true
                        val hasChildren = node.children.isNotEmpty()
                        val isSelected = selectedNode == node

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) Color(0xFF264F78) else Color.Transparent)
                                .clickable {
                                    selectedNode = node
                                }
                                .padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasChildren) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                    contentDescription = "Expand/Collapse",
                                    tint = Color.LightGray,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            expandedStates[node] = !isExpanded
                                        }
                                )
                            } else {
                                Spacer(Modifier.width(16.dp))
                            }
                            
                            Spacer(Modifier.width(4.dp))
                            
                            // Class Name
                            val simpleClassName = node.className.substringAfterLast('.')
                            Text(simpleClassName, color = Color(0xFF569CD6), fontSize = 13.sp)
                            
                            // Resource ID or Text
                            if (node.resourceId.isNotEmpty()) {
                                val simpleResId = node.resourceId.substringAfterLast('/')
                                Text(" #$simpleResId", color = Color(0xFFFFC66D), fontSize = 12.sp)
                            } else if (node.text.isNotEmpty()) {
                                Text(" \"${node.text}\"", color = Color(0xFF6A8759), fontSize = 12.sp)
                            } else if (node.contentDescription.isNotEmpty()) {
                                Text(" {${node.contentDescription}}", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(listState)
                )
            }
            
            HorizontalDivider(color = Color(0xFF3C3F41))
            
            // Details View
            Box(modifier = Modifier.weight(0.4f).fillMaxWidth().padding(8.dp)) {
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text("Node Details", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                        selectedNode?.let { node ->
                            Text("Class: ${node.className}", color = Color(0xFF569CD6), fontSize = 13.sp)
                            Text("Resource ID: ${node.resourceId.ifEmpty { "N/A" }}", color = Color(0xFFFFC66D), fontSize = 13.sp)
                            Text("Text: ${node.text.ifEmpty { "N/A" }}", color = Color(0xFF6A8759), fontSize = 13.sp)
                            Text("Content Desc: ${node.contentDescription.ifEmpty { "N/A" }}", color = Color.Gray, fontSize = 13.sp)
                            Text("Package: ${node.packageName}", color = Color.LightGray, fontSize = 13.sp)
                            Text("Bounds: [${node.bounds.left}, ${node.bounds.top}][${node.bounds.right}, ${node.bounds.bottom}]", color = Color.LightGray, fontSize = 13.sp)
                            
                            Spacer(Modifier.height(4.dp))
                            Text("Properties:", color = Color.White, fontSize = 13.sp)
                            val props = mutableListOf<String>()
                            if (node.checkable) props.add("checkable")
                            if (node.checked) props.add("checked")
                            if (node.clickable) props.add("clickable")
                            if (node.enabled) props.add("enabled")
                            if (node.focusable) props.add("focusable")
                            if (node.focused) props.add("focused")
                            if (node.scrollable) props.add("scrollable")
                            if (node.longClickable) props.add("longClickable")
                            if (node.password) props.add("password")
                            if (node.selected) props.add("selected")
                            Text(props.joinToString(", ").ifEmpty { "None" }, color = Color.LightGray, fontSize = 13.sp)
                        } ?: Text("No node selected", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }

        // Right Pane: Wireframe Visualizer
        Box(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            WireframeVisualizer(rootNode = rootNode, selectedNode = selectedNode, screenshot = screenshot, onNodeSelected = { selectedNode = it })
        }
    }
}

/** Helper data class to keep track of depth during flattening */
data class FlatNode(val node: UiNode, val depth: Int, val parent: UiNode?)

fun flattenTree(node: UiNode, depth: Int = 0, parent: UiNode? = null): List<FlatNode> {
    val list = mutableListOf(FlatNode(node, depth, parent))
    for (child in node.children) {
        list.addAll(flattenTree(child, depth + 1, node))
    }
    return list
}

fun isVisible(flatNode: FlatNode, allNodes: List<FlatNode>, expandedStates: Map<UiNode, Boolean>): Boolean {
    var currentParent = flatNode.parent
    while (currentParent != null) {
        if (expandedStates[currentParent] != true) {
            return false // A parent is collapsed
        }
        // Find the parent's parent
        val parentNodeData = allNodes.find { it.node == currentParent }
        currentParent = parentNodeData?.parent
    }
    return true
}

fun findDeepestNodeAt(node: UiNode, x: Float, y: Float, scaleX: Float, scaleY: Float): UiNode? {
    val left = node.bounds.left * scaleX
    val top = node.bounds.top * scaleY
    val right = node.bounds.right * scaleX
    val bottom = node.bounds.bottom * scaleY

    if (x in left..right && y in top..bottom) {
        // First, check children from last to first (visually on top usually)
        for (i in node.children.indices.reversed()) {
            val childMatch = findDeepestNodeAt(node.children[i], x, y, scaleX, scaleY)
            if (childMatch != null) return childMatch
        }
        
        // If no child matched, check if THIS node is clickable.
        val isInteractive = node.clickable || node.longClickable || node.checkable || node.scrollable
        
        if (isInteractive) {
             return node
        }
        
        // If it's just a transparent wrapper layout holding nothing, ignore it so we can pierce through, 
        // but typically findDeepestNodeAt stops at the first hit. To pierce through perfectly overlapping bounds,
        // returning null here allows the loop in the parent to continue checking siblings.
        return null
    }
    return null
}

@Composable
fun WireframeVisualizer(rootNode: UiNode, selectedNode: UiNode?, screenshot: ImageBitmap?, onNodeSelected: (UiNode) -> Unit) {
    // 画面全体（ルート）のサイズを取得 (eg. 1080x2400)
    // ダイアログ等のオーバーレイが表示された場合、ルートノードのBoundsが画面全体よりも小さくなり、
    // 背景のスクリーンショットと座標ズレを起こす問題を防ぐため、常にディスプレイサイズ（画像サイズ等）を一次情報にする。
    val rootWidth = screenshot?.width ?: Math.max(1080, rootNode.bounds.right)
    val rootHeight = screenshot?.height ?: Math.max(2400, rootNode.bounds.bottom)

    if (rootWidth <= 0 || rootHeight <= 0) {
        Text("Invalid bounds for root node.", color = Color.Red)
        return
    }

    val aspectRatio = rootWidth.toFloat() / rootHeight.toFloat()

    // 画面の比率を維持したまま、利用可能な領域内で最大化して描画
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(aspectRatio)
            .border(2.dp, Color.DarkGray)
            .background(Color.Black)
    ) {
        if (screenshot != null) {
            androidx.compose.foundation.Image(
                bitmap = screenshot,
                contentDescription = "Device Screenshot",
                modifier = Modifier.fillMaxSize()
            )
        }
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(rootNode) {
                detectTapGestures { offset ->
                    val scaleX = size.width.toFloat() / rootWidth.toFloat()
                    val scaleY = size.height.toFloat() / rootHeight.toFloat()
                    val matchedNode = findDeepestNodeAt(rootNode, offset.x, offset.y, scaleX, scaleY)
                    if (matchedNode != null) {
                        onNodeSelected(matchedNode)
                    }
                }
            }
        ) {
            val scaleX = size.width / rootWidth
            val scaleY = size.height / rootHeight

            // Recursively draw nodes
            fun drawNode(node: UiNode) {
                // Ignore nodes with 0 dimensions
                if (node.bounds.right - node.bounds.left <= 0 || node.bounds.bottom - node.bounds.top <= 0) return

                val left = node.bounds.left * scaleX
                val top = node.bounds.top * scaleY
                val width = (node.bounds.right - node.bounds.left) * scaleX
                val height = (node.bounds.bottom - node.bounds.top) * scaleY

                // Check if this is the selected node
                if (node == selectedNode) {
                    drawRect(
                        color = Color(0x66569CD6), // Semi-transparent blue fill
                        topLeft = Offset(left, top),
                        size = Size(width, height)
                    )
                    drawRect(
                        color = Color(0xFF569CD6), // Solid blue border
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 3.dp.toPx())
                    )
                } else {
                    // Draw normal outlines
                    drawRect(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Draw children
                node.children.forEach { drawNode(it) }
            }

            // Start drawing from root
            drawNode(rootNode)
            
            // Re-draw the selected node on top so its border is clearly visible above siblings
            selectedNode?.let {
                val left = it.bounds.left * scaleX
                val top = it.bounds.top * scaleY
                val width = (it.bounds.right - it.bounds.left) * scaleX
                val height = (it.bounds.bottom - it.bounds.top) * scaleY
                
                drawRect(
                    color = Color.Red, // Make the border red when selected to stand out from the blue fill
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
    }
}
