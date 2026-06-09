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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// JetLime imports
import com.pushpal.jetlime.JetLimeRow
import com.pushpal.jetlime.JetLimeEvent
import com.pushpal.jetlime.JetLimeEventDefaults
import com.pushpal.jetlime.ItemsList
import com.pushpal.jetlime.JetLimeDefaults

import org.example.project.model.UiNode
import org.example.project.model.TimelineItem

@Composable
fun UiInspectorPane(
    rootNode: UiNode?,
    screenshot: ImageBitmap? = null,
    screenWidth: Int = 1080,
    screenHeight: Int = 2400,
    timelineItems: List<TimelineItem> = emptyList(),
    selectedTimelineIndex: Int = -1,
    onSelectTimelineIndex: (TimelineItem) -> Unit = {},
    onPerformTap: (UiNode) -> Unit = {}
) {
    var selectedNode by remember { mutableStateOf<UiNode?>(null) }
    var detailsNode by remember { mutableStateOf<UiNode?>(null) }
    
    // Auto-select root when it loads and reset selection if root changes
    LaunchedEffect(rootNode) {
        selectedNode = rootNode
    }

    if (rootNode == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22)), contentAlignment = Alignment.Center) {
            Text("No UI Dump available. Waiting for automatic polling...", color = Color.Gray)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {
        // Top Pane: JetLime Multiplatform Linear Timeline UI
        TimelineBar(
            items = timelineItems,
            selectedIndex = selectedTimelineIndex,
            onSelectItem = onSelectTimelineIndex
        )

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Left Pane: Expanded Tree View
            Column(modifier = Modifier.weight(0.5f).fillMaxHeight().border(1.dp, Color(0xFF3C3F41))) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                            val visibleNodes = flatNodes.filter { isVisible(it, flatNodes, expandedStates) }
                            val selectedIndex = visibleNodes.indexOfFirst { it.node == node }
                            if (selectedIndex >= 0) {
                                listState.animateScrollToItem(selectedIndex)
                            }
                        }
                    }

                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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

                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Show Details",
                                    tint = Color.LightGray.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            detailsNode = node
                                        }
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(listState)
                    )
                }
            }

            // Right Pane: Wireframe Visualizer + Interaction Area
            Column(
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    WireframeVisualizer(
                        rootNode = rootNode,
                        selectedNode = selectedNode,
                        screenshot = screenshot,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        onNodeSelected = { selectedNode = it }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        selectedNode?.let { onPerformTap(it) }
                    },
                    enabled = selectedNode != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF569CD6),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF3C3F41),
                        disabledContentColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text(
                        text = if (selectedNode != null) {
                            val x = (selectedNode!!.bounds.left + selectedNode!!.bounds.right) / 2
                            val y = (selectedNode!!.bounds.top + selectedNode!!.bounds.bottom) / 2
                            "Tap Element at ($x, $y)"
                        } else {
                            "Select an element to Tap"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Node Details Dialog (Popup overlay)
    if (detailsNode != null) {
        val node = detailsNode!!
        AlertDialog(
            onDismissRequest = { detailsNode = null },
            confirmButton = {
                TextButton(onClick = { detailsNode = null }) {
                    Text("Close", color = Color(0xFF569CD6))
                }
            },
            title = {
                Text("Node Details", color = Color.White, fontSize = 15.sp)
            },
            text = {
                SelectionContainer {
                    Column {
                        Text("Class: ${node.className}", color = Color(0xFF569CD6), fontSize = 13.sp)
                        Text("Resource ID: ${node.resourceId.ifEmpty { "N/A" }}", color = Color(0xFFFFC66D), fontSize = 13.sp)
                        Text("Text: ${node.text.ifEmpty { "N/A" }}", color = Color(0xFF6A8759), fontSize = 13.sp)
                        Text("Content Desc: ${node.contentDescription.ifEmpty { "N/A" }}", color = Color.Gray, fontSize = 13.sp)
                        Text("Package: ${node.packageName}", color = Color.LightGray, fontSize = 13.sp)
                        Text("Bounds: [${node.bounds.left}, ${node.bounds.top}][${node.bounds.right}, ${node.bounds.bottom}]", color = Color.LightGray, fontSize = 13.sp)
                        
                        Spacer(Modifier.height(8.dp))
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
                    }
                }
            },
            containerColor = Color(0xFF2B2D30)
        )
    }
}

@Composable
fun TimelineBar(
    items: List<TimelineItem>,
    selectedIndex: Int,
    onSelectItem: (TimelineItem) -> Unit
) {
    if (items.isEmpty()) return

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val selectedItem = items.getOrNull(selectedIndex)

    // Default Theme Blue color for line and points
    val lineColor = Color(0xFF569CD6)
    val rowStyle = JetLimeDefaults.rowStyle(
        lineBrush = androidx.compose.ui.graphics.SolidColor(lineColor),
        lineThickness = 2.dp
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D30)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header showing start and latest timeline timestamps
            val firstTime = items.firstOrNull()?.timestamp?.let { timeFormatter.format(Date(it)) } ?: "N/A"
            val lastTime = items.lastOrNull()?.timestamp?.let { timeFormatter.format(Date(it)) } ?: "N/A"
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Timeline History",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Range: $firstTime ➔ $lastTime",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(85.dp) // Height fits larger circle/square points and centered timestamps
            ) {
                JetLimeRow(
                    itemsList = ItemsList(items),
                    style = rowStyle,
                    modifier = Modifier.fillMaxSize(),
                    key = { _, item -> item.id }
                ) { index, item, position ->
                    val record = item as? TimelineItem.Record ?: return@JetLimeRow
                    val isSelected = selectedItem != null && record.id == selectedItem.id
                    
                    // Force the very first item (origin) to always render a pin even if it has no UI changes
                    val hasChange = record.hasChange || index == 0

                    val timeStr = timeFormatter.format(Date(record.timestamp))
                    
                    // Width is 60dp, which centers the text exactly below PointPlacement.CENTER
                    val baseModifier = Modifier
                        .width(60.dp)
                        .height(30.dp)

                    if (!hasChange) {
                        // Linear time progression point without changes (just a passing line)
                        // Uses LinePainter custom icon to keep the timeline connection seamless (no gaps)
                        JetLimeEvent(
                            style = JetLimeEventDefaults.eventStyle(
                                position = position,
                                pointType = com.pushpal.jetlime.EventPointType.custom(
                                    LinePainter(lineColor, 2.dp)
                                ),
                                pointRadius = 10.dp, // Matches pointRadius to align the timeline line height perfectly
                                pointPlacement = com.pushpal.jetlime.PointPlacement.CENTER,
                                pointColor = Color.Transparent,
                                pointStrokeColor = Color.Transparent
                            )
                        ) {
                            Box(modifier = baseModifier)
                        }
                    } else {
                        // Event node: either background change or click action
                        val isAction = record.actionDetails != null
                        val pinColor = lineColor
                        val pointColor = if (isSelected) Color.White else pinColor

                        // Size is significantly increased for visual clarity (Circle radius=8dp, Square size=20dp)
                        val pointType = if (isAction) {
                            com.pushpal.jetlime.EventPointType.custom(SquarePainter(pointColor, 20.dp))
                        } else {
                            com.pushpal.jetlime.EventPointType.custom(CirclePainter(pointColor, 8.dp))
                        }
                        
                        // Enforce a constant pointRadius = 10.dp for ALL events.
                        // Since JetLime uses pointRadius to calculate the height offset (yOffset) of the timeline line,
                        // keeping it constant guarantees the connection line stays perfectly straight and does not skip vertically.
                        val radius = 10.dp

                        // We pass clickable Modifier directly to JetLimeEvent to make the circle shape area clickable.
                        // PointPlacement.CENTER guarantees that the shape is aligned at the horizontal center of 60dp width,
                        // meaning the text below aligns perfectly without any custom padding!
                        JetLimeEvent(
                            modifier = Modifier.clickable { onSelectItem(record) },
                            style = JetLimeEventDefaults.eventStyle(
                                position = position,
                                pointType = pointType,
                                pointRadius = radius,
                                pointPlacement = com.pushpal.jetlime.PointPlacement.CENTER,
                                pointColor = Color.Transparent,
                                pointStrokeColor = Color.Transparent
                            )
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = baseModifier
                            ) {
                                Text(
                                    text = timeStr,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
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
        for (i in node.children.indices.reversed()) {
            val childMatch = findDeepestNodeAt(node.children[i], x, y, scaleX, scaleY)
            if (childMatch != null) return childMatch
        }
        val isInteractive = node.clickable || node.longClickable || node.checkable || node.scrollable
        if (isInteractive) {
             return node
        }
        return null
    }
    return null
}

@Composable
fun WireframeVisualizer(
    rootNode: UiNode,
    selectedNode: UiNode?,
    screenshot: ImageBitmap?,
    screenWidth: Int,
    screenHeight: Int,
    onNodeSelected: (UiNode) -> Unit
) {
    val rootWidth = screenWidth
    val rootHeight = screenHeight

    if (rootWidth <= 0 || rootHeight <= 0) {
        Text("Invalid bounds for root node.", color = Color.Red)
        return
    }

    val aspectRatio = rootWidth.toFloat() / rootHeight.toFloat()

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

            fun drawNode(node: UiNode) {
                if (node.bounds.right - node.bounds.left <= 0 || node.bounds.bottom - node.bounds.top <= 0) return

                val left = node.bounds.left * scaleX
                val top = node.bounds.top * scaleY
                val width = (node.bounds.right - node.bounds.left) * scaleX
                val height = (node.bounds.bottom - node.bounds.top) * scaleY

                if (node == selectedNode) {
                    drawRect(
                        color = Color(0x66569CD6),
                        topLeft = Offset(left, top),
                        size = Size(width, height)
                    )
                    drawRect(
                        color = Color(0xFF569CD6),
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 3.dp.toPx())
                    )
                } else {
                    drawRect(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                node.children.forEach { drawNode(it) }
            }

            drawNode(rootNode)
            
            selectedNode?.let {
                val left = it.bounds.left * scaleX
                val top = it.bounds.top * scaleY
                val width = (it.bounds.right - it.bounds.left) * scaleX
                val height = (it.bounds.bottom - it.bounds.top) * scaleY
                
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
    }
}

// Custom painters to draw custom timeline point shapes and seamlessly bridge empty slots
private class SquarePainter(private val color: Color, private val sizeDp: androidx.compose.ui.unit.Dp) : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize: Size = Size(20f, 20f)
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val s = sizeDp.toPx()
        drawRect(
            color = color,
            topLeft = Offset((size.width - s) / 2, (size.height - s) / 2),
            size = Size(s, s)
        )
    }
}

private class CirclePainter(private val color: Color, private val radius: androidx.compose.ui.unit.Dp) : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize: Size = Size(20f, 20f)
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val r = radius.toPx()
        drawCircle(
            color = color,
            radius = r,
            center = Offset(size.width / 2, size.height / 2)
        )
    }
}

private class LinePainter(private val color: Color, private val thickness: androidx.compose.ui.unit.Dp) : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize: Size = Size(30f, 6f)
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val y = size.height / 2
        val th = thickness.toPx()
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = th
        )
    }
}
