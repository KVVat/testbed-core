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
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.ktor.server.routing.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import com.google.gson.Gson
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.example.project.adb.AdbObserver
import org.example.project.AppViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.application.call

class McpSseServer(private val adbObserver: AdbObserver, private val appViewModel: AppViewModel) {

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
            description = "Starts execution of the specified test class or method."
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
            description = "Retrieves test execution results (Pass/Fail/Error info, stacktrace, etc) in JSON. Returns interim logs and progress if Running."
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
            name = "start_stream",
            description = "Start screenshot stream"
        ) { _ ->
            adbObserver.startScreenshotStream(fps = 1f)
            CallToolResult(content = listOf(TextContent("Stream started")))
        }

        mcpServer.addTool(
            name = "stop_stream",
            description = "Stop screenshot stream"
        ) { _ ->
            adbObserver.stopScreenshotStream()
            CallToolResult(content = listOf(TextContent("Stream stopped")))
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
            description = "Retrieves the current UI hierarchy (JSON) and screenshot image (Base64)."
        ) { request ->
            val args = request.arguments as? Map<*, *>
            val includeImageObj = args?.get("include_image")
            val includeImage = includeImageObj?.toString()?.toBoolean() ?: false
            val result = adbObserver.dumpMuttonAgent(includeImage)
            CallToolResult(content = listOf(TextContent(result ?: "Error: Failed to get UI dump")))
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
            description = "Physically taps the specified (x, y) coordinates. *Automatically waits for idle and returns the latest UI dump after execution."
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val result = adbObserver.tapCoordinate(x, y)
            CallToolResult(content = listOf(TextContent(result ?: "Error: Failed to tap")))
        }

        mcpServer.addTool(
            name = "input_text",
            description = "Inputs text into the currently focused input field. *Automatically waits for idle and returns the latest UI dump after execution."
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val pressEnter = args["press_enter"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val result = adbObserver.inputText(text, pressEnter)
            CallToolResult(content = listOf(TextContent(result ?: "Error: Failed to input text")))
        }

        mcpServer.addTool(
            name = "swipe",
            description = "Swipes (scrolls) the screen between the specified coordinates. *Automatically waits for idle and returns the latest UI dump after execution."
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val sx = args["start_x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val sy = args["start_y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val ex = args["end_x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val ey = args["end_y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val result = adbObserver.swipe(sx, sy, ex, ey)
            CallToolResult(content = listOf(TextContent(result ?: "Error: Failed to swipe")))
        }

        mcpServer.addTool(
            name = "press_key",
            description = "Sends a physical or system key event. *Automatically waits for idle and returns the latest UI dump after execution."
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val keycode = args["keycode"]?.jsonPrimitive?.contentOrNull ?: ""
            val result = adbObserver.pressKey(keycode)
            CallToolResult(content = listOf(TextContent(result ?: "Error: Failed to press key")))
        }

        mcpServer.addTool(
            name = "get_device_info",
            description = "Retrieves device hardware and OS information (wrapper for getprop)."
        ) { _ ->
            val info = adbObserver.getDeviceInfo()
            CallToolResult(content = listOf(TextContent(info)))
        }

        mcpServer.addTool(
            name = "clear_logcat",
            description = "Clears the device's Logcat buffer (adb logcat -c)."
        ) { _ ->
            val result = adbObserver.clearLogcatBuffer()
            CallToolResult(content = listOf(TextContent(result)))
        }

        mcpServer.addTool(
            name = "get_logcat",
            description = "Retrieves filtered Logcat lines. (Essential for saving tokens)"
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            val tags = args["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val level = args["level"]?.jsonPrimitive?.contentOrNull ?: "I"
            val grepPattern = args["grep_pattern"]?.jsonPrimitive?.contentOrNull ?: ""
            // max_lines could be passed as JSON primitive string or Int, handle both
            val maxLines = args["max_lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() 
                ?: args["max_lines"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() 
                ?: 100

            val result = adbObserver.getFilteredLogcat(tags, level, grepPattern, maxLines)
            CallToolResult(content = listOf(TextContent(result)))
        }

        return mcpServer
    }
    

    private val serverSessions = ConcurrentMap<String, ServerSession>()

    fun start(port: Int = 11452) {
        if (serverEngine != null) return

        val mcpServer = configureServer()

        serverEngine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            installCors()
            install(SSE)
            install(IgnoreTrailingSlash)
            
            routing {
                val sseHandler: suspend io.ktor.server.sse.ServerSSESession.() -> Unit = {
                    val transport = SseServerTransport("/mcp/message", this)
                    appViewModel.log("MCP", "New SSE connection. Sending endpoint: /mcp/message?sessionId=${transport.sessionId}")
                    
                    // JetSkiから再接続(リロード)された際に、古いセッションが残っていると
                    // 後続の fallback POST がそちらを掴んでしまうバグを防ぐためクリアする
                    serverSessions.clear()
                    
                    val serverSession = mcpServer.createSession(transport)
                    serverSessions[transport.sessionId] = serverSession

                    serverSession.onInitialized {
                        appViewModel.viewModelScope.launch(Dispatchers.IO) {
                            try {
                                adbObserver.setupMuttonAgent(forceInstall = false)
                                appViewModel.log("MCP", "Agent background verification started.")
                            } catch (e: Exception) {
                                appViewModel.log("MCP", "Failed during background agent verification: ${e.message}", org.example.project.LogLevel.ERROR)
                            }
                        }
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

                    val transport = serverSessions[sessionId]?.transport as? SseServerTransport
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
                    
                    appViewModel.log("MCP", "Successfully routed POST to session $sessionId")
                    appViewModel.log("MCP", "Incoming Payload: $body")
                    
                    try {
                        transport.handleMessage(body)
                        
                        // JetSkiの `initialize` 要求は同期レスポンスを求めるため横取りしてモックを返す
                        val idMatch = "\"id\"\\s*:\\s*(\\d+|\\\".*?\\\")".toRegex().find(body)
                        val idVal = idMatch?.groupValues?.get(1) ?: "1"

                        if (body.contains("\"method\":\"initialize\"") || body.contains("\"method\": \"initialize\"")) {
                            // Kotlin SDK側内部で非同期に初期化が完了するのを待つため、JetSkiへのmock返却をあえて遅延させる
                            // （JetSkiが爆速で tools/list を要求し、未初期化エラーで破棄される race condition を防ぐため）
                            kotlinx.coroutines.delay(500)
                            
                            val mockResponse = """{"jsonrpc": "2.0", "id": $idVal, "result": {"protocolVersion": "2024-11-05", "capabilities": {"prompts": {"listChanged": true}, "resources": {"subscribe": true, "listChanged": true}, "tools": {"listChanged": true}}, "serverInfo": {"name": "testbed-core", "version": "1.0.0"}}}"""
                            call.respondText(mockResponse, io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } else {
                            // ダミーのNotification（id無し）を返すことで、同期Promiseを消費せず、デコーダーのエラーを回避
                            call.respondText("""{"jsonrpc": "2.0", "method": "dummy"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.Accepted)
                        }
                    } catch (e: Exception) {
                        appViewModel.log("MCP", "Error handling message: ${e.message}")
                        call.respondText("{\"error\":\"Error handling message\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }
                
                // Fallback catch-all for JetSki's weird POST requests
                post("/mcp") {
                    val rawBody = call.receiveText()
                    
                    // JetSkiのSSEクライアントがイベントリッスン準備を完全に終えるまでの猶予として無条件に2秒待機
                    kotlinx.coroutines.delay(2000)
                    
                    var activeSession = serverSessions.values.firstOrNull()
                    var retries = 0
                    // 念のためセッションがない場合も追加で待機（最大2秒）
                    while (activeSession == null && retries < 20) {
                        kotlinx.coroutines.delay(100)
                        activeSession = serverSessions.values.firstOrNull()
                        retries++
                    }
                    val transport = activeSession?.transport as? io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
                    
                    if (transport != null) {
                        try {
                            // 受信した生のメッセージリストを転送
                            transport.handleMessage(rawBody)
                            
                            val idMatch = "\"id\"\\s*:\\s*(\\d+|\\\".*?\\\")".toRegex().find(rawBody)
                            val idVal = idMatch?.groupValues?.get(1) ?: "1"

                            if (rawBody.contains("\"method\":\"initialize\"") || rawBody.contains("\"method\": \"initialize\"")) {
                                // Kotlin SDK側内部で非同期に初期化が完了するのを待つため、JetSkiへのmock返却をあえて遅延させる
                                kotlinx.coroutines.delay(500)
                                
                                val mockResponse = """{"jsonrpc": "2.0", "id": $idVal, "result": {"protocolVersion": "2024-11-05", "capabilities": {"prompts": {"listChanged": true}, "resources": {"subscribe": true, "listChanged": true}, "tools": {"listChanged": true}}, "serverInfo": {"name": "testbed-core", "version": "1.0.0"}}}"""
                                call.respondText(mockResponse, io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                            } else {
                                // ダミーのNotification（id無し）を返すことで、同期Promiseを消費せず、デコーダーのエラーを回避
                                call.respondText("""{"jsonrpc": "2.0", "method": "dummy"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Accepted)
                            }
                        } catch (e: Exception) {
                            appViewModel.log("MCP", "Error handling fallback POST: ${e.message}", org.example.project.LogLevel.ERROR)
                            call.respondText("{\"error\":\"Bad Request\"}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                        }
                    } else {
                        appViewModel.log("MCP", "No active SSE session to route JetSki's POST to (timed out).", org.example.project.LogLevel.WARN)
                        // Return a mock initialize response as a last resort to appease JetSki's synchronous expectations
                        if (rawBody.contains("\"method\":\"initialize\"")) {
                            val response = """{"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2024-11-05", "capabilities": {}, "serverInfo": {"name": "testbed-mock-fallback", "version": "1.0.0"}}}"""
                            call.respondText(response, io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
                        } else {
                            call.respondText("{}", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.NotFound)
                        }
                    }
                }
            }
        }.start(wait = false)

        println("MCP SSE Server started on http://0.0.0.0:$port/mcp")
    }

    fun stop() {
        serverEngine?.stop(1000, 2000)
        serverEngine = null
        serverSessions.clear()
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
