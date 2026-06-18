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
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwitchLeft
import androidx.compose.material.icons.filled.SwitchRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Image
import org.example.project.mcp.UiDumpSummarizer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.ContentCopy
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

import org.example.project.model.UiNode
import org.example.project.model.TimelineItem

@Composable
fun UiInspectorPane(viewModel: ToolViewModel) {
    val rootNodeState by viewModel.uiDumpRoot.collectAsState()
    val screenshot by viewModel.uiDumpScreenshot.collectAsState()
    val screenWidth by viewModel.uiDumpScreenWidth.collectAsState()
    val screenHeight by viewModel.uiDumpScreenHeight.collectAsState()
    val timelineItems by viewModel.timelineItems.collectAsState()
    val selectedTimelineIndex by viewModel.selectedTimelineIndex.collectAsState()
    val inspectorMode by viewModel.inspectorMode.collectAsState()
    val uiDumpLoadingState by viewModel.uiDumpLoadingState.collectAsState()
    val activeLayoutTime by viewModel.activeLayoutTime.collectAsState()
    val isInteracting by viewModel.isAgentInteracting.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    var selectedNode by remember { mutableStateOf<UiNode?>(null) }
    var detailsNode by remember { mutableStateOf<UiNode?>(null) }
    
    // Auto-select root when it loads and reset selection if root changes
    LaunchedEffect(rootNodeState) {
        selectedNode = null
    }

    if (rootNodeState == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22)), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF569CD6),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = uiDumpLoadingState,
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        return
    }

    val rootNode = rootNodeState!!

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {


        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Left Pane: Wireframe Visualizer + Interaction Area (元 Right Pane)
            Column(
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WireframeVisualizer(
                        rootNode = rootNode,
                        selectedNode = selectedNode,
                        screenshot = screenshot,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        onNodeSelected = { selectedNode = it },
                        viewModel = viewModel
                    )

                    if (isInteracting) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .pointerInput(Unit) {
                                    detectTapGestures { /* Intercept all click gestures to prevent double taps */ }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = Color(0xFF569CD6),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Interacting...",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Floating Hardware Keys Overlay (Top End)
                    if (inspectorMode == 0) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .background(Color(0xCC2B2D30), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF3C3F41), RoundedCornerShape(4.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val keys = listOf(
                                Triple("BACK", Icons.Default.ArrowBack, "Back Key"),
                                Triple("HOME", Icons.Default.Home, "Home Key"),
                                Triple("APP_SWITCH", Icons.Default.Apps, "Recents Key")
                            )
                            keys.forEach { (code, icon, desc) ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .alpha(if (isInteracting) 0.5f else 1f)
                                        .background(Color(0xFF4C5052), RoundedCornerShape(4.dp))
                                        .clickable(enabled = !isInteracting) { viewModel.pressHardwareKey(code) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = desc,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Floating Mode Selector Overlay (Top Start)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color(0xCC2B2D30), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF3C3F41), RoundedCornerShape(4.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(if (inspectorMode == 0) Color(0xFF569CD6) else Color(0xFF4C5052), RoundedCornerShape(4.dp))
                                .clickable { viewModel.setInspectorMode(0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = "Interaction Mode",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(if (inspectorMode == 1) Color(0xFF569CD6) else Color(0xFF4C5052), RoundedCornerShape(4.dp))
                                .clickable { viewModel.setInspectorMode(1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Inspection Mode",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Right Pane: UI Tree View / History (元 Left Pane)
            Column(modifier = Modifier.weight(0.5f).fillMaxHeight().border(1.dp, Color(0xFF3C3F41))) {
                val leftPanelMode by viewModel.leftPanelMode.collectAsState()
                val showSimpleTree by viewModel.showSimpleTree.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2B2D30))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .border(1.dp, Color(0xFF3C3F41)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (inspectorMode == 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // History button to the left
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(if (leftPanelMode == 1) Color(0xFF569CD6) else Color(0xFF4C5052), RoundedCornerShape(4.dp))
                                    .clickable { viewModel.setLeftPanelMode(1) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.List,
                                    contentDescription = "History View Mode",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            // AccountTree button to the right
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(if (leftPanelMode == 0) Color(0xFF569CD6) else Color(0xFF4C5052), RoundedCornerShape(4.dp))
                                    .clickable { viewModel.setLeftPanelMode(0) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountTree,
                                    contentDescription = "UI Tree View Mode",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Text(
                        text = "Record : $activeLayoutTime",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val chooser = javax.swing.JFileChooser().apply {
                                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "Select Export Destination Directory"
                                }
                                val result = chooser.showSaveDialog(null)
                                if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    val targetDir = chooser.selectedFile
                                    viewModel.saveCurrentLayoutSource(targetDir)
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Export Source Layout",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (inspectorMode == 0 || leftPanelMode == 0) {
                        val listState = rememberLazyListState()
                        
                        if (showSimpleTree) {
                            val flatNodes = remember(rootNode) { UiDumpSummarizer.getSummaryLines(rootNode) }
                            
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(flatNodes) { lineText ->
                                    val match = remember(lineText) { Regex("""(tap|at)\((\d+),(\d+)\)""").find(lineText) }
                                    val isSelected = match?.let {
                                        val x = it.groupValues[2].toFloat()
                                        val y = it.groupValues[3].toFloat()
                                        selectedNode?.let { sel ->
                                            val cx = (sel.bounds.left + sel.bounds.right) / 2f
                                            val cy = (sel.bounds.top + sel.bounds.bottom) / 2f
                                            Math.abs(cx - x) < 2 && Math.abs(cy - y) < 2
                                        }
                                    } == true

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) Color(0xFF264F78) else Color.Transparent)
                                            .clickable {
                                                match?.let {
                                                    val x = it.groupValues[2].toFloat()
                                                    val y = it.groupValues[3].toFloat()
                                                    val matched = findDeepestNodeAt(rootNode, x, y, 1f, 1f)
                                                    if (matched != null) {
                                                        selectedNode = matched
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = lineText,
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        
                                        match?.let {
                                            val x = it.groupValues[2].toFloat()
                                            val y = it.groupValues[3].toFloat()
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = "Show Details",
                                                tint = Color.LightGray.copy(alpha = 0.5f),
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clickable {
                                                        val matched = findDeepestNodeAt(rootNode, x, y, 1f, 1f)
                                                        if (matched != null) {
                                                            detailsNode = matched
                                                        }
                                                    }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(1.dp).fillMaxWidth().background(Color(0xFF2B2D30)))
                                }
                            }
                        } else {
                            val flatNodes = remember(rootNode) { flattenTree(rootNode) }
                            val expandedStates = remember { mutableStateMapOf<UiNode, Boolean>(rootNode to true) }

                            LaunchedEffect(selectedNode) {
                                selectedNode?.let { node ->
                                    var currentOwner = flatNodes.find { it.node == node }?.parent
                                    while (currentOwner != null) {
                                        expandedStates[currentOwner] = true
                                        currentOwner = flatNodes.find { it.node == currentOwner }?.parent
                                    }

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
                                            .clickable { selectedNode = node }
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
                                                    .clickable { expandedStates[node] = !isExpanded }
                                            )
                                        } else {
                                            Spacer(Modifier.width(16.dp))
                                        }
                                        
                                        Spacer(Modifier.width(4.dp))
                                        
                                        val simpleClassName = node.className.substringAfterLast('.')
                                        Text(simpleClassName, color = Color(0xFF569CD6), fontSize = 13.sp)
                                        
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
                                                .clickable { detailsNode = node }
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Floating Tree Mode Toggle Button (Top End inside Right Panel Box)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color(0xCC2B2D30), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF3C3F41), RoundedCornerShape(4.dp))
                        ) {
                            IconButton(
                                onClick = { viewModel.toggleSimpleTree() },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = if (showSimpleTree) Icons.Default.SwitchLeft else Icons.Default.SwitchRight,
                                    contentDescription = "Toggle Detailed/Flat Tree Mode",
                                    tint = if (showSimpleTree) Color(0xFF569CD6) else Color.LightGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(listState)
                        )
                    } else {
                        val historyItems by viewModel.layoutHistory.collectAsState()
                        val listState = rememberLazyListState()

                        val groupedItems = remember(historyItems) {
                            historyItems.groupBy { it.displayTime.take(10) }
                        }

                        val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

                        LaunchedEffect(groupedItems) {
                            groupedItems.keys.forEachIndexed { index, date ->
                                if (!expandedStates.containsKey(date)) {
                                    expandedStates[date] = (index == 0)
                                }
                            }
                        }

                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                            groupedItems.forEach { (date, itemsForDate) ->
                                item(key = date) {
                                    val isExpanded = expandedStates[date] ?: false
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { expandedStates[date] = !isExpanded }
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                                contentDescription = "Toggle Collapse",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = date,
                                                color = Color.LightGray,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Text(
                                            text = "${itemsForDate.size} items",
                                            color = Color.Gray,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                if (expandedStates[date] == true) {
                                    items(itemsForDate, key = { it.uuid }) { item ->
                                        val imageFile = item.pngFile

                                        val imageBitmapState = produceState<ImageBitmap?>(initialValue = null, key1 = imageFile) {
                                            if (imageFile != null && imageFile.exists()) {
                                                withContext(Dispatchers.IO) {
                                                    try {
                                                        val bytes = imageFile.readBytes()
                                                        val composeBitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                                                        value = composeBitmap
                                                    } catch (e: Exception) {
                                                        value = null
                                                    }
                                                }
                                            } else {
                                                value = null
                                            }
                                        }
                                        val imageBitmap = imageBitmapState.value

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .background(Color(0xFF2B2D30), RoundedCornerShape(4.dp))
                                                .border(1.dp, Color(0xFF3C3F41), RoundedCornerShape(4.dp))
                                                .clickable { viewModel.selectHistoryItem(item) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Thumbnail preview
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp, 60.dp)
                                                    .background(Color(0xFF1E1F22), RoundedCornerShape(2.dp))
                                                    .border(0.5.dp, Color(0xFF3C3F41), RoundedCornerShape(2.dp))
                                                    .clip(RoundedCornerShape(2.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (imageBitmap != null) {
                                                    Image(
                                                        bitmap = imageBitmap,
                                                        contentDescription = "Preview screenshot",
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.ImageNotSupported,
                                                        contentDescription = "No Preview",
                                                        tint = Color.Gray.copy(alpha = 0.5f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }

                                            // Content Text Column
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(start = 10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.History,
                                                            contentDescription = "Archived Artifact",
                                                            tint = Color(0xFF569CD6),
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Text(
                                                            text = item.displayTime.substring(11), // Time only since Date Header already shows yyyy-MM-dd
                                                            color = Color.White,
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                        if (item.tag != null) {
                                                            Text(
                                                                text = "[${item.tag}]",
                                                                color = Color(0xFFFFC66D),
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(Modifier.height(4.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "UUID: ${item.uuid}",
                                                        color = Color.Gray,
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        modifier = Modifier.weight(1f)
                                                    )

                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.AccountTree,
                                                            contentDescription = "Load and View Tree",
                                                            tint = Color.LightGray.copy(alpha = 0.8f),
                                                            modifier = Modifier
                                                                .size(14.dp)
                                                                .clickable {
                                                                    viewModel.selectHistoryItem(item)
                                                                    viewModel.setLeftPanelMode(0)
                                                                }
                                                        )

                                                        Icon(
                                                            imageVector = Icons.Default.ContentCopy,
                                                            contentDescription = "Copy UUID",
                                                            tint = Color.LightGray.copy(alpha = 0.6f),
                                                            modifier = Modifier
                                                                .size(12.dp)
                                                                .clickable {
                                                                    clipboardManager.setText(AnnotatedString(item.uuid))
                                                                    viewModel.showSnackbar("Copied UUID to Clipboard")
                                                                }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            }
                        }

                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(listState)
                        )
                    }
                }
            }
        }

        // Bottom Status Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2B2D30))
                .border(1.dp, Color(0xFF3C3F41))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Active Mode: ${if (inspectorMode == 0) "Interaction" else "Inspection"}",
                color = Color.LightGray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
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





fun filterSimpleNode(node: UiNode): UiNode? {
    val filteredChildren = node.children.mapNotNull { filterSimpleNode(it) }
    val isMeaningful = node.text.isNotEmpty() ||
                       node.contentDescription.isNotEmpty() ||
                       node.clickable || node.checkable || node.longClickable ||
                       node.resourceId.isNotEmpty()
                       
    if (isMeaningful || filteredChildren.isNotEmpty()) {
        return node.copy(children = filteredChildren)
    }
    return null
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
    onNodeSelected: (UiNode) -> Unit,
    viewModel: ToolViewModel
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
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No Screenshot Available",
                    color = Color.LightGray.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(rootNode) {
                detectTapGestures { offset ->
                    val scaleX = size.width.toFloat() / rootWidth.toFloat()
                    val scaleY = size.height.toFloat() / rootHeight.toFloat()
                    val inspectorMode = viewModel.inspectorMode.value
                    
                    if (inspectorMode == 0) {
                        val tapX = (offset.x / scaleX).toInt()
                        val tapY = (offset.y / scaleY).toInt()
                        viewModel.performCoordinateTap(tapX, tapY)
                    } else {
                        val matchedNode = findDeepestNodeAt(rootNode, offset.x, offset.y, scaleX, scaleY)
                        if (matchedNode != null) {
                            viewModel.setLeftPanelMode(0)
                            onNodeSelected(matchedNode)
                        }
                    }
                }
            }
        ) {
            val scaleX = size.width / rootWidth
            val scaleY = size.height / rootHeight

            fun drawNode(node: UiNode) {
                if (viewModel.inspectorMode.value == 0) return
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

            if (viewModel.inspectorMode.value != 0) {
                drawNode(rootNode)
            }
            
            if (viewModel.inspectorMode.value != 0) {
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
}


