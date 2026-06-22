package org.example.project.mcp

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

expect fun readSystemInLine(): String?
expect fun printStderr(message: String)
expect fun flushStdout()

fun main(args: Array<String>) {
    var host = "localhost"
    var port = 11452
    
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> {
                host = args.getOrNull(i + 1) ?: host
                i += 2
            }
            "--port" -> {
                port = args.getOrNull(i + 1)?.toIntOrNull() ?: port
                i += 2
            }
            else -> i++
        }
    }
    
    val ktorMcpUrl = "http://$host:$port/mcp"
    printStderr("✅ Stdio Bridge Initializing: Targeting $ktorMcpUrl")
    
    runBlocking {
        val bridge = StdioBridge(ktorMcpUrl)
        bridge.start()
    }
}

class StdioBridge(private val ktorMcpUrl: String) {
    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 5000
            requestTimeoutMillis = 120000
        }
    }
    
    private var sessionUrl: String? = null
    private var sseConnected = false
    private var cachedToolsResponse: String? = null
    
    suspend fun start() = coroutineScope {
        launch(Dispatchers.Default) {
            runSseListener()
        }
        
        printStderr("✅ TestBed Core MCP Stdio Bridge (Kotlin/Multiplatform) Started.")
        
        while (isActive) {
            val line = readSystemInLine()?.trim() ?: break
            if (line.isNotEmpty()) {
                handleInputLine(line)
            }
        }
    }
    
    private suspend fun runSseListener() {
        while (true) {
            try {
                client.prepareGet(ktorMcpUrl) {
                    header("Accept", "text/event-stream")
                }.execute { response ->
                    if (response.status == HttpStatusCode.OK) {
                        val channel = response.bodyAsChannel()
                        while (!channel.isClosedForRead) {
                            val line = channel.readUTF8Line() ?: break
                            val trimmed = line.trim()
                            if (trimmed.startsWith("data:")) {
                                val dataContent = trimmed.substring(5).trim()
                                if (!sseConnected && (dataContent.contains("sessionId=") || dataContent.contains("session_id="))) {
                                    val baseUrl = ktorMcpUrl.substringBefore("/mcp")
                                    sessionUrl = "$baseUrl$dataContent"
                                    sseConnected = true
                                    printStderr("✅ Stdio Bridge: SSE Session Established. Endpoint=$sessionUrl")
                                    
                                    fetchAndCacheTools()
                                } else {
                                    if (dataContent.contains("jsonrpc")) {
                                        if (dataContent.contains(""""id"\s*:\s*999""".toRegex()) || dataContent.contains(""""id":999""")) {
                                            cachedToolsResponse = dataContent
                                            printStderr("✅ Stdio Bridge: Dynamic tools catalog cached from SSE channel.")
                                        } else {
                                            println(dataContent)
                                            flushStdout()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                sseConnected = false
                sessionUrl = null
                printStderr("⚠️ Stdio Bridge: Connection lost: ${e.message}. Retrying in 2 seconds...")
                delay(2000)
            }
        }
    }
    
    private suspend fun fetchAndCacheTools() {
        val url = sessionUrl ?: return
        try {
            val payload = """{"jsonrpc":"2.0","method":"tools/list","id":999}"""
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            printStderr("✅ Stdio Bridge: Requested tools/list for caching.")
        } catch (e: Exception) {
            printStderr("⚠️ Stdio Bridge: Failed to trigger tools/list request: ${e.message}")
        }
    }
    
    private suspend fun handleInputLine(line: String) {
        try {
            val reqId = extractJsonValue(line, "id")
            val method = extractJsonValue(line, "method")
            
            if (reqId == null) return
            
            if (method == "initialize") {
                val resp = """{"jsonrpc":"2.0","result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"testbed-core","version":"1.0.0"}},"id":$reqId}"""
                println(resp)
                flushStdout()
                return
            }
            
            if (method == "tools/list") {
                var waitCount = 10
                while (cachedToolsResponse == null && waitCount > 0 && !sseConnected) {
                    delay(100)
                    waitCount--
                }
                
                val cached = cachedToolsResponse
                if (cached != null) {
                    val replaced = cached.replace(Regex(""""id"\s*:\s*999"""), """"id":$reqId""")
                    println(replaced)
                    flushStdout()
                } else {
                    val dummyList = """{"jsonrpc":"2.0","result":{"tools":[{"name":"backend_server_not_running_warning","description":"⚠️ WARNING: TestBed Core backend Ktor server is NOT running. Please start the TestBed Core desktop app first.","inputSchema":{"type":"object","properties":{}}}]},"id":$reqId}"""
                    println(dummyList)
                    flushStdout()
                }
                return
            }
            
            if (method == "tools/call") {
                val toolName = extractJsonValue(line, "name")
                if (toolName == "backend_server_not_running_warning") {
                    var checkCount = 50
                    while (!sseConnected && checkCount > 0) {
                        delay(100)
                        checkCount--
                    }
                    
                    val message = if (sseConnected) {
                        "✅ Notice: The TestBed Core backend server is now ONLINE and running properly. Please use the other active tools for device control."
                    } else {
                        "⚠️ Error: The TestBed Core backend server is NOT running on the host machine. Please launch the TestBed Core Desktop App to enable full device control and verification capabilities."
                    }
                    val resp = """{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"$message"}]},"id":$reqId}"""
                    println(resp)
                    flushStdout()
                    return
                }
            }
            
            forwardRequest(line, reqId)
        } catch (e: Exception) {
            printStderr("Error processing line: ${e.message}")
        }
    }
    
    private suspend fun forwardRequest(line: String, reqId: String) {
        var waitCount = 50
        while (!sseConnected && waitCount > 0) {
            delay(100)
            waitCount--
        }
        
        val url = sessionUrl
        if (url == null) {
            val err = """{"jsonrpc":"2.0","error":{"code":-32603,"message":"Error: TestBed Core backend Ktor server is unreachable."},"id":$reqId}"""
            println(err)
            flushStdout()
            return
        }
        
        try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(line)
            }
            if (response.status != HttpStatusCode.OK) {
                println(response.bodyAsText())
                flushStdout()
            }
        } catch (e: Exception) {
            val err = """{"jsonrpc":"2.0","error":{"code":-32603,"message":"Error forwarding request: ${e.message}"},"id":$reqId}"""
            println(err)
            flushStdout()
        }
    }
    
    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*(?:"([^"]+)"|([0-9]+)|true|false)""")
        val match = pattern.find(json) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value ?: match.value.substringAfter(":").trim()
    }
}
