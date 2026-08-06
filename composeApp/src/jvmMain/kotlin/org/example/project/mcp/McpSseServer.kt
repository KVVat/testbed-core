package org.example.project.mcp

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.ktor.server.routing.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import com.google.gson.Gson
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlinx.serialization.json.putJsonArray
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import org.example.project.adb.AdbObserver
import org.example.project.MainViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.application.call

class McpSseServer(private val adbObserver: AdbObserver, private val appViewModel: MainViewModel) {

    private var mcpServerInstance: Server? = null

    private fun getMockToolsJson(idVal: String): String {
        val toolsList = mcpServerInstance?.tools?.values?.map { it.tool } ?: emptyList()
        val listToolsResult = io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult(tools = toolsList, nextCursor = null)
        val resultJson = kotlinx.serialization.json.Json.encodeToString(listToolsResult)
        
        val idStr = if (idVal.toIntOrNull() != null) idVal else "\"$idVal\""
        
        return """{"jsonrpc":"2.0","id":$idStr,"result":$resultJson}"""
    }

private var serverEngine: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    fun configureServer(): Server {
        val mcpServer = Server(
            Implementation(
                name = "testbed-core",
                version = "1.0.0"
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        mcpServer.addTool(
            name = "check_testbed_health",
            description = "Check ADB connection, device online status, and agent status all at once."
        ) { _ ->
            val state = appViewModel.uiState.value
            val info = mapOf(
                "adbIsValid" to state.adbIsValid,
                "isUnauthorized" to state.isUnauthorized,
                "deviceSerial" to state.deviceSerial,
                "isRunning" to state.isRunning,
                "deviceInfo" to state.deviceInfo
            )
            CallToolResult(content = listOf(TextContent(Gson().toJson(info))))
        }

        mcpServer.addTool(
            name = "cleanup_agent",
            description = "Force-stops the agent process on the device for cleanup. Useful for recovery when UiAutomation errors occur."
        ) { _ ->
            adbObserver.cleanupMuttonAgent()
            CallToolResult(content = listOf(TextContent("Agent cleanup requested. Process force-stopped.")))
        }

        mcpServer.addTool(
            name = "junit_test_reload",
            description = "Reloads the test JAR."
        ) { _ ->
            appViewModel.refreshPlugins()
            CallToolResult(content = listOf(TextContent("{\"status\":\"reloading\"}")))
        }

        mcpServer.addTool(
            name = "junit_test_list",
            description = "Returns a JSON array of the currently loaded tests."
        ) { _ ->
            val list = appViewModel.testPlugins.map { 
                mapOf(
                    "name" to it.name,
                    "className" to it.className,
                    "shortName" to it.shortName
                )
            }
            CallToolResult(content = listOf(TextContent(Gson().toJson(list))))
        }

        mcpServer.addTool(
            name = "junit_test_execute",
            description = "Starts execution of the specified test class or method.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class_name") { put("type", "string"); put("description", "Fully qualified test class name") }
                    putJsonObject("method_name") { put("type", "string"); put("description", "Optional: specific test method name") }
                },
                required = listOf("class_name")
            )
        ) { request ->
            val args = request.params.arguments
            val className = args?.get("class_name")?.jsonPrimitive?.contentOrNull 
                ?: return@addTool CallToolResult(content = listOf(TextContent("{\"error\":\"class_name is required\"}")))
            val methodName = args["method_name"]?.jsonPrimitive?.contentOrNull
            
            appViewModel.runTestForMcp(className, methodName)
            CallToolResult(content = listOf(TextContent("{\"status\":\"started\", \"class_name\":\"$className\", \"method_name\":${methodName?.let{"\"$it\""} ?: "null"}}")))
        }

        mcpServer.addTool(
            name = "junit_test_receive",
            description = "Retrieves test execution results (Pass/Fail/Error info, stacktrace, etc) in JSON. Returns interim logs and progress if Running.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("last_log_index") { put("type", "integer"); put("description", "Index to retrieve only new logs since last call. Default 0.") }
                }
            )
        ) { request ->
            val args = request.params.arguments
            val lastLogIndex = args?.get("last_log_index")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

            val results = appViewModel.mcpTestResults.toList()
            val status = if (appViewModel.uiState.value.isRunning) "Running" else "Finished"
            
            val logs = appViewModel.mcpTestLogs.toList()
            val newLogs = if (lastLogIndex < logs.size) logs.subList(lastLogIndex, logs.size) else emptyList()

            val response = mutableMapOf<String, Any>(
                "status" to status,
                "results" to results,
                "logs" to newLogs,
                "current_step" to appViewModel.currentTestStep,
                "progress_percent" to appViewModel.currentTestProgress,
                "next_log_index" to logs.size
            )
            CallToolResult(content = listOf(TextContent(Gson().toJson(response))))
        }


        mcpServer.addTool(
            name = "get_screen",
            description = "Captures a screenshot of the connected device. Resizes the image to fit within a 1024x1024 bounding box (maintaining aspect ratio) and compresses it as a JPEG. If a tag is provided, the layout and screenshot are saved to the history DB.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tag") { put("type", "string"); put("description", "Optional custom string key to tag the saved screen layout in history registry.") }
                }
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val tag = args["tag"]?.jsonPrimitive?.contentOrNull

            // Request high-quality original screenshot and UI dump from Mutton agent (quality = 1, includeImage = true)
            val rawResult = adbObserver.dumpMuttonAgent(includeImage = true, quality = 1, silent = true)
            if (rawResult == null) {
                return@addTool CallToolResult(content = listOf(TextContent("Error: Failed to communicate with the Mutton agent on the device.")))
            }

            val gson = Gson()
            val dumpResult = try {
                gson.fromJson(rawResult, org.example.project.model.DumpResult::class.java)
            } catch (e: Exception) {
                null
            }

            if (dumpResult == null || dumpResult.status != "ok" || dumpResult.screenshot.isNullOrEmpty()) {
                return@addTool CallToolResult(content = listOf(TextContent("Error: Failed to capture screenshot from the device.")))
            }

            // Perform image scaling and JPEG compression on the host side to keep it under 1024px bounding box
            val resizedBase64 = org.example.project.tools.ImageUtil.resizeBase64Image(dumpResult.screenshot, maxDimension = 1024, jpegQuality = 0.75f)
            if (resizedBase64 == null) {
                return@addTool CallToolResult(content = listOf(TextContent("Error: Failed to process and compress captured screenshot on host.")))
            }

            // Save layout and scaled screenshot in history DB if a tag is specified
            if (tag != null) {
                try {
                    org.example.project.model.LayoutDatabase.saveLayoutArtifact(
                        jsonLayout = dumpResult.output,
                        screenshotBase64 = resizedBase64,
                        tag = tag
                    )
                } catch (e: Exception) {
                    System.err.println("[MCP] Failed to save layout artifact for get_screen: ${e.message}")
                }
            }

            // Return purely the ImageContent format
            CallToolResult(content = listOf(ImageContent(data = resizedBase64, mimeType = "image/jpeg")))
        }

        mcpServer.addTool(
            name = "ping",
            description = "Ping the mutton agent"
        ) { _ ->
            adbObserver.pingMuttonAgent()
            CallToolResult(content = listOf(TextContent("Ping sent")))
        }

        mcpServer.addTool(
            name = "get_ui_dump",
            description = "Retrieves the current UI hierarchy. Default format is 'summary' (compact flat list optimized for LLMs, ~1KB). If execution does not complete in 1 second, it returns a task_id for receive_ui_dump.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("format") { put("type", "string"); put("description", "Output format: 'summary' (compact flat list, default) or 'json' (full tree)") }
                    putJsonObject("include_image") { put("type", "boolean"); put("description", "Include screenshot. Default false") }
                    putJsonObject("image_quality") { put("type", "integer"); put("description", "1=100%, 2=50%, 3=33%, 4=25%. Default 4 (25%)") }
                    putJsonObject("tag") { put("type", "string"); put("description", "Optional custom string key to tag the saved layout in history registry.") }
                }
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "summary"
            val includeImage = args["include_image"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val quality = args["image_quality"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 4
            val tag = args["tag"]?.jsonPrimitive?.contentOrNull

            val rawResult = adbObserver.uiDumpExecute(format, includeImage, quality, tag)
            CallToolResult(content = listOf(TextContent(rawResult)))
        }

        mcpServer.addTool(
            name = "get_ui_dump_history",
            description = "Retrieves a historical UI layout dump by UUID, Tag, or latest relative Index.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("uuid") { put("type", "string"); put("description", "Query layout directly by its unique UUID (Highest precedence)") }
                    putJsonObject("tag") { put("type", "string"); put("description", "Retrieve the latest layout saved with this tag (Second precedence)") }
                    putJsonObject("index") { put("type", "integer"); put("description", "Relative index offset from latest. 0 = latest, 1 = 1-step-back, etc. (Default 0)") }
                    putJsonObject("format") { put("type", "string"); put("description", "Format option: 'summary' (optimized, default) or 'json' (raw)") }
                }
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val uuid = args["uuid"]?.jsonPrimitive?.contentOrNull
            val tag = args["tag"]?.jsonPrimitive?.contentOrNull
            val index = args["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "summary"

            val record = when {
                !uuid.isNullOrBlank() -> org.example.project.model.LayoutDatabase.getRecordByUuid(uuid)
                !tag.isNullOrBlank() -> org.example.project.model.LayoutDatabase.getLatestRecordByTag(tag)
                else -> org.example.project.model.LayoutDatabase.getRecordByOffset(index)
            }

            if (record == null) {
                CallToolResult(content = listOf(TextContent("Error: No layout artifact matched the specified query parameters.")))
            } else {
                val baseDirStr = org.example.project.JUnitBridge.baseDir.ifBlank { "." }
                val savedDir = java.io.File(baseDirStr, "saved_layouts")
                val jsonFile = java.io.File(savedDir, record.jsonFilepath)
                
                if (!jsonFile.exists()) {
                    CallToolResult(content = listOf(TextContent("Error: Layout file not found on disk at: ${jsonFile.absolutePath}")))
                } else {
                    val jsonText = jsonFile.readText()
                    val outputText = if (format == "summary") {
                        try {
                            val node = Gson().fromJson(jsonText, org.example.project.model.UiNode::class.java)
                            UiDumpSummarizer.getSummaryLines(node).joinToString("\n")
                        } catch (e: Exception) {
                            "Error parsing JSON layout for summary: ${e.message}\n\n$jsonText"
                        }
                    } else {
                        jsonText
                    }
                    
                    val pngFile = record.pngFilepath?.let { java.io.File(savedDir, it) }
                    val pngPathInfo = if (pngFile != null && pngFile.exists()) {
                        ", png_path: ${pngFile.absolutePath}"
                    } else {
                        ""
                    }
                    val metadataFooter = "\n\n[Artifact metadata -> uuid: ${record.uuid}" +
                            (if (record.tag != null) ", tag: ${record.tag}" else "") +
                            ", timestamp: ${record.timestamp}$pngPathInfo]"
                    
                    CallToolResult(content = listOf(TextContent(outputText + metadataFooter)))
                }
            }
        }

        mcpServer.addTool(
            name = "receive_ui_dump",
            description = "Retrieve the results of a pending get_ui_dump task.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("task_id") { put("type", "string"); put("description", "The task ID returned by get_ui_dump") }
                },
                required = listOf("task_id")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val result = adbObserver.uiDumpReceive(taskId)
            CallToolResult(content = listOf(TextContent(result)))
        }

        mcpServer.addTool(
            name = "get_agent_version",
            description = "Retrieves the version information of the Testbed agent (Mutton Agent)."
        ) { _ ->
            val version = adbObserver.getMuttonAgentVersion()
            CallToolResult(content = listOf(TextContent(version ?: "Unknown")))
        }

        mcpServer.addTool(
            name = "tap",
            description = "Physically taps the specified (x, y) coordinates. Does not return the updated UI dump; call get_ui_dump separately if needed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x") { put("type", "integer"); put("description", "X coordinate to tap") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate to tap") }
                },
                required = listOf("x", "y")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val rawResult = adbObserver.tapCoordinate(x, y)
            CallToolResult(content = listOf(TextContent(rawResult ?: "Error: Failed to tap")))
        }

        mcpServer.addTool(
            name = "input_text",
            description = "Inputs text into the currently focused input field. Does not return the updated UI dump; call get_ui_dump separately if needed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("text") { put("type", "string"); put("description", "Text to input (ASCII only)") }
                    putJsonObject("press_enter") { put("type", "boolean"); put("description", "Press Enter after input. Default true") }
                },
                required = listOf("text")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val pressEnter = args["press_enter"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val rawResult = adbObserver.inputText(text, pressEnter)
            CallToolResult(content = listOf(TextContent(rawResult ?: "Error: Failed to input text")))
        }

        mcpServer.addTool(
            name = "swipe",
            description = "Swipes (scrolls) the screen between the specified coordinates. Does not return the updated UI dump; call get_ui_dump separately if needed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("start_x") { put("type", "integer"); put("description", "Start X coordinate") }
                    putJsonObject("start_y") { put("type", "integer"); put("description", "Start Y coordinate") }
                    putJsonObject("end_x") { put("type", "integer"); put("description", "End X coordinate") }
                    putJsonObject("end_y") { put("type", "integer"); put("description", "End Y coordinate") }
                },
                required = listOf("start_x", "start_y", "end_x", "end_y")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val sx = args["start_x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val sy = args["start_y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val ex = args["end_x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val ey = args["end_y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val rawResult = adbObserver.swipe(sx, sy, ex, ey)
            CallToolResult(content = listOf(TextContent(rawResult ?: "Error: Failed to swipe")))
        }

        mcpServer.addTool(
            name = "press_key",
            description = "Sends a physical or system key event. Does not return the updated UI dump; call get_ui_dump separately if needed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("keycode") { put("type", "string"); put("description", "Key name: HOME, BACK, ENTER, POWER, VOLUME_UP, VOLUME_DOWN, etc. Or numeric keycode as string.") }
                },
                required = listOf("keycode")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val keycode = args["keycode"]?.jsonPrimitive?.contentOrNull ?: ""
            val rawResult = adbObserver.pressKey(keycode)
            CallToolResult(content = listOf(TextContent(rawResult ?: "Error: Failed to press key")))
        }

        mcpServer.addTool(
            name = "get_device_info",
            description = "Retrieves device hardware and OS information (wrapper for getprop)."
        ) { _ ->
            val info = adbObserver.getDeviceInfo()
            CallToolResult(content = listOf(TextContent(info)))
        }

        mcpServer.addTool(
            name = "execute_adb_shell",
            description = "Executes an adb shell command directly against the connected device. If execution takes more than 1 second, it returns immediately with a task_id and status='running'. You must use shell_receive to check and fetch the output.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("command") { put("type", "string"); put("description", "Shell command to execute") }
                },
                required = listOf("command")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val command = args["command"]?.jsonPrimitive?.contentOrNull ?: ""
            if (command.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Error: command parameter is required.")))
            } else {
                val result = adbObserver.shellExecute(command)
                CallToolResult(content = listOf(TextContent(result)))
            }
        }

        mcpServer.addTool(
            name = "shell_receive",
            description = "Retrieves results of a background shell task started via execute_adb_shell.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("task_id") { put("type", "string"); put("description", "Task ID returned by execute_adb_shell") }
                },
                required = listOf("task_id")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull ?: ""
            if (taskId.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Error: task_id parameter is required.")))
            } else {
                val result = adbObserver.shellReceive(taskId)
                CallToolResult(content = listOf(TextContent(result)))
            }
        }

        mcpServer.addTool(
            name = "get_device_state",
            description = "Retrieves screen ON/OFF, lock state, and foreground package."
        ) { _ ->
            val state = adbObserver.getDeviceState()
            CallToolResult(content = listOf(TextContent(state)))
        }

        mcpServer.addTool(
            name = "open_settings",
            description = "Opens a specific settings panel on the device. Does not return the updated UI dump; call get_ui_dump separately if needed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("panel") { put("type", "string"); put("description", "Panel name: ROOT, SECURITY, WIFI, DEVELOPER, APP_DETAILS") }
                    putJsonObject("package_name") { put("type", "string"); put("description", "Required for APP_DETAILS panel") }
                },
                required = listOf("panel")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val panel = args["panel"]?.jsonPrimitive?.contentOrNull ?: ""
            val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull
            if (panel.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Error: panel parameter is required.")))
            } else {
                val rawResult = adbObserver.openSettings(panel, packageName)
                CallToolResult(content = listOf(TextContent(rawResult ?: "Error: Failed to open settings.")))
            }
        }

        mcpServer.addTool(
            name = "push_file",
            description = "Pushes a file from the host PC to the device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("host_path") { put("type", "string"); put("description", "Absolute path on host PC") }
                    putJsonObject("device_path") { put("type", "string"); put("description", "Absolute path on device") }
                },
                required = listOf("host_path", "device_path")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val hostPath = args["host_path"]?.jsonPrimitive?.contentOrNull ?: ""
            val devicePath = args["device_path"]?.jsonPrimitive?.contentOrNull ?: ""
            if (hostPath.isEmpty() || devicePath.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Error: host_path and device_path are required.")))
            } else {
                val result = adbObserver.pushFile(hostPath, devicePath)
                CallToolResult(content = listOf(TextContent(result)))
            }
        }

        mcpServer.addTool(
            name = "pull_file",
            description = "Pulls a file from the device to the host PC.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("device_path") { put("type", "string"); put("description", "Absolute path on device") }
                    putJsonObject("host_path") { put("type", "string"); put("description", "Absolute path on host PC") }
                },
                required = listOf("device_path", "host_path")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val devicePath = args["device_path"]?.jsonPrimitive?.contentOrNull ?: ""
            val hostPath = args["host_path"]?.jsonPrimitive?.contentOrNull ?: ""
            if (devicePath.isEmpty() || hostPath.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Error: device_path and host_path are required.")))
            } else {
                val result = adbObserver.pullFile(devicePath, hostPath)
                CallToolResult(content = listOf(TextContent(result)))
            }
        }

        mcpServer.addTool(
            name = "install_app",
            description = "Installs an APK from the host PC to the device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("apk_path") { put("type", "string"); put("description", "Absolute path to APK on host PC") }
                    putJsonObject("reinstall") { put("type", "boolean"); put("description", "Reinstall if already installed. Default true") }
                },
                required = listOf("apk_path")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val apkPath = args["apk_path"]?.jsonPrimitive?.contentOrNull ?: ""
            val reinstall = args["reinstall"]?.jsonPrimitive?.booleanOrNull ?: true
            if (apkPath.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Error: apk_path is required.")))
            } else {
                val result = adbObserver.installApp(apkPath, reinstall)
                CallToolResult(content = listOf(TextContent(result)))
            }
        }

        mcpServer.addTool(
            name = "uninstall_app",
            description = "Uninstalls an app from the device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("package_name") { put("type", "string"); put("description", "Package name to uninstall") }
                    putJsonObject("keep_data") { put("type", "boolean"); put("description", "Keep app data after uninstall. Default false") }
                },
                required = listOf("package_name")
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull ?: ""
            val keepData = args["keep_data"]?.jsonPrimitive?.booleanOrNull ?: false
            if (packageName.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Error: package_name is required.")))
            } else {
                val result = adbObserver.uninstallApp(packageName, keepData)
                CallToolResult(content = listOf(TextContent(result)))
            }
        }

        mcpServer.addTool(
            name = "get_logcat",
            description = "Retrieves filtered Logcat lines. 100x faster with zero process-spawning overhead, highly recommended over running raw 'adb shell logcat' via execute_adb_shell. Essential for token savings.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("tags") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Log tag names to filter by") }
                    putJsonObject("level") { put("type", "string"); put("description", "Minimum log level: V, D, I, W, E, F. Default V") }
                    putJsonObject("grep_pattern") { put("type", "string"); put("description", "Grep pattern for filtering") }
                    putJsonObject("max_lines") { put("type", "integer"); put("description", "Maximum lines to return. Default 100") }
                    putJsonObject("process") { put("type", "string"); put("description", "Filter by process: package name (e.g. com.android.settings) or PID. Package names are auto-resolved to PID.") }
                }
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val tags = args["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val level = args["level"]?.jsonPrimitive?.contentOrNull ?: "V"
            val grepPattern = args["grep_pattern"]?.jsonPrimitive?.contentOrNull ?: ""
            val process = args["process"]?.jsonPrimitive?.contentOrNull ?: ""
            // max_lines could be passed as JSON primitive string or Int, handle both
            val maxLines = args["max_lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() 
                ?: args["max_lines"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() 
                ?: 100

            val result = adbObserver.getFilteredLogcat(tags, level, grepPattern, maxLines, process)
            CallToolResult(content = listOf(TextContent(result)))
        }

        return mcpServer
    }
    

    data class SessionHolder(
        val sessionId: String,
        val session: ServerSession,
        var lastActivityTime: Long = System.currentTimeMillis()
    )

    private val serverSessions = ConcurrentMap<String, SessionHolder>()

    fun start(host: String = "0.0.0.0", port: Int = 11452) {
        if (serverEngine != null) return

        val mcpServer = configureServer()
        mcpServerInstance = mcpServer

        try {
            val engine = embeddedServer(CIO, host = host, port = port) {
                installCors()
                install(SSE)
                install(IgnoreTrailingSlash)
                
                // Session cleanup job (120 minutes expiration)
                appViewModel.viewModelScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        kotlinx.coroutines.delay(60000) // Check every minute
                        val now = System.currentTimeMillis()
                        val expiredIds = serverSessions.filter { now - it.value.lastActivityTime > 120 * 60 * 1000 }.keys
                        if (expiredIds.isNotEmpty()) {
                            appViewModel.log("MCP", "Removing expired sessions: $expiredIds")
                            expiredIds.forEach { serverSessions.remove(it) }
                        }
                    }
                }

                routing {
                    val sseHandler: suspend io.ktor.server.sse.ServerSSESession.() -> Unit = {
                        val transport = SseServerTransport("/mcp/message", this)
                        val serverSession = mcpServer.createSession(transport)
                        serverSessions[transport.sessionId] = SessionHolder(transport.sessionId, serverSession)

                        serverSession.onInitialized {
                            appViewModel.log("MCP", "Server session initialized for: ${transport.sessionId}")
                        }

                        serverSession.onClose {
                            appViewModel.log("MCP", "Server session closed for: ${transport.sessionId}")
                            serverSessions.remove(transport.sessionId)
                        }
                        kotlinx.coroutines.awaitCancellation()
                    }

                    sse("/mcp", sseHandler)
                    
                    sse("/mcp/test_logs") {
                        println("Client connected to /mcp/test_logs")
                        val session = this
                        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            appViewModel.logFlow.collect { logLine ->
                                val data = Gson().toJson(mapOf(
                                    "time" to logLine.timestamp,
                                    "level" to logLine.level.name,
                                    "message" to "[${logLine.tag}] ${logLine.message}"
                                ))
                                session.send(io.ktor.sse.ServerSentEvent(data = data))
                            }
                        }
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            job.cancel()
                            println("Client disconnected from /mcp/test_logs")
                        }
                    }
                    
                    post("/mcp/message") {
                        appViewModel.log("MCP", "Incoming POST request to /mcp/message")
                        val sessionId: String? = call.request.queryParameters["sessionId"]
                        if (sessionId == null) {
                            appViewModel.log("MCP", "Error: Missing sessionId parameter")
                            call.respond(HttpStatusCode.BadRequest, "Missing sessionId parameter")
                            return@post
                        }

                        val sessionHolder = serverSessions[sessionId]
                        val transport = sessionHolder?.session?.transport as? SseServerTransport
                        if (transport == null) {
                            appViewModel.log("MCP", "Error: Session not found for ID: '$sessionId' (Active sessions: ${serverSessions.keys})")
                            call.respond(HttpStatusCode.NotFound, "Session not found")
                            return@post
                        }

                        val body = try {
                            call.receiveText()
                        } catch (e: Exception) {
                            appViewModel.log("MCP", "Error reading request body: ${e.message}")
                            call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
                            return@post
                        }
                        
                        sessionHolder.lastActivityTime = System.currentTimeMillis()
                        appViewModel.log("MCP", "Successfully routed POST to session $sessionId (Updated activity)")
                        appViewModel.log("MCP", "Incoming Payload: $body")
                        
                        try {
                            transport.handleMessage(body)
                            
                            // JetSkiの `initialize` 要求は同期レスポンスを求めるため横取りしてモックを返す
                            val idMatch = "\"id\"\\s*:\\s*(\\d+|\\\".*?\\\")".toRegex().find(body)
                            val idVal = idMatch?.groupValues?.get(1) ?: "1"

                            if (body.contains("\"method\":\"initialize\"") || body.contains("\"method\": \"initialize\"")) {
                                // Kotlin SDK側内部で非同期に初期化が完了するのを待つため、少しだけ遅延させる
                                kotlinx.coroutines.delay(200)
                                
                                val mockResponse = """{"jsonrpc": "2.0", "id": $idVal, "result": {"protocolVersion": "2024-11-05", "capabilities": {"prompts": {"listChanged": true}, "resources": {"subscribe": true, "listChanged": true}, "tools": {"listChanged": true}}, "serverInfo": {"name": "testbed-core", "version": "1.0.0"}}}"""
                                call.respondText(mockResponse, io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                            } else {
                                // ダミーのNotification（id無し）を返すことで、同期Promiseを消費せず、デコーダーのエラーを回避
                                call.respondText("""{"jsonrpc": "2.0", "method": "dummy"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Accepted)
                            }
                        } catch (e: Exception) {
                            appViewModel.log("MCP", "Error handling message: ${e.message}")
                            call.respondText("{\"error\":\"Error handling message\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                        }
                    }

                    // Fallback catch-all for JetSki's weird POST requests
                    post("/mcp") {
                        if (!appViewModel.appSettings.value.useMcpFallback) {
                            call.respondText("Fallback disabled", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound)
                            return@post
                        }
                        val rawBody = call.receiveText()
                        appViewModel.log("MCP", "Incoming Fallback Payload: $rawBody")
                        
                        val idMatch = "\"id\"\\s*:\\s*(\\d+|\\\".*?\\\")".toRegex().find(rawBody)
                        val idVal = idMatch?.groupValues?.get(1) ?: "1"

                        // Handle initialize request even without active session
                        if (rawBody.contains("\"method\":\"initialize\"") || rawBody.contains("\"method\": \"initialize\"")) {
                            val activeSessionHolder = serverSessions.values.firstOrNull()
                            val activeSession = activeSessionHolder?.session
                            val transport = activeSession?.transport as? io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
                            
                            if (transport != null) {
                                try {
                                    transport.handleMessage(rawBody)
                                    activeSessionHolder.lastActivityTime = System.currentTimeMillis()
                                } catch (e: Exception) {
                                    appViewModel.log("MCP", "Error handling initialize message: ${e.message}")
                                }
                            } else {
                                appViewModel.log("MCP", "Received initialize POST without active session. Proceeding with mock response.")
                            }
                            
                            kotlinx.coroutines.delay(200)
                            val mockResponse = """{"jsonrpc": "2.0", "id": $idVal, "result": {"protocolVersion": "2024-11-05", "capabilities": {"prompts": {"listChanged": true}, "resources": {"subscribe": true, "listChanged": true}, "tools": {"listChanged": true}}, "serverInfo": {"name": "testbed-core", "version": "1.0.0"}}}"""
                            call.respondText(mockResponse, io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                            return@post
                        }

                        if (rawBody.contains("\"method\":\"tools/list\"") || rawBody.contains("\"method\": \"tools/list\"")) {
                            val mockToolsResponse = getMockToolsJson(idVal)
                            call.respondText(mockToolsResponse, io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                            return@post
                        }

                        // Original logic for other messages
                        var activeSessionHolder = serverSessions.values.maxByOrNull { it.lastActivityTime }
                        var retries = 0
                        // セッションがない場合のみ最大2秒待機して探す
                        while (activeSessionHolder == null && retries < 20) {
                            kotlinx.coroutines.delay(100)
                            activeSessionHolder = serverSessions.values.maxByOrNull { it.lastActivityTime }
                            retries++
                        }
                        
                        val activeSession = activeSessionHolder?.session
                        val transport = activeSession?.transport as? io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
                        
                        if (activeSessionHolder != null) {
                            activeSessionHolder.lastActivityTime = System.currentTimeMillis()
                            appViewModel.log("MCP", "Fallback POST using session: ${activeSessionHolder.sessionId}")
                        }
                        
                        if (transport != null) {
                            try {
                                transport.handleMessage(rawBody)
                                call.respondText("""{"jsonrpc": "2.0", "method": "dummy"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Accepted)
                            } catch (e: Exception) {
                                appViewModel.log("MCP", "Error handling fallback POST: ${e.message}", org.example.project.LogLevel.ERROR)
                                call.respondText("{\"error\":\"Bad Request\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                            }
                        } else {
                            // タイムアウトした場合は、速やかに404を返してクライアントに再接続を促す
                            appViewModel.log("MCP", "No active SSE session to route JetSki's POST to (timed out). Rejecting immediately.", org.example.project.LogLevel.WARN)
                            call.respondText("Session not found", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound)
                        }
                    }
                }
            }
            serverEngine = engine.start(wait = false)
            appViewModel.log("MCP", "MCP SSE Server started on http://$host:$port/mcp", org.example.project.LogLevel.PASS)
        } catch (e: Exception) {
            appViewModel.log("MCP", "Failed to start MCP SSE Server on $host:$port: ${e.message}", org.example.project.LogLevel.ERROR)
            System.err.println("[MCP] Failed to start MCP SSE Server on $host:$port: ${e.message}")
        }
    }

    fun stop() {
        serverEngine?.stop(1000, 2000)
        serverEngine = null
        serverSessions.clear()
        appViewModel.log("MCP", "MCP SSE Server stopped.", org.example.project.LogLevel.INFO)
    }

    private fun Application.installCors() {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowNonSimpleContentTypes = true
            anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
        }
    }

}
