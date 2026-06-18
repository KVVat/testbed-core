package org.example.project.mcp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

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
    System.err.println("✅ Stdio Bridge Initializing: Targeting $ktorMcpUrl")
    
    val bridge = StdioBridge(ktorMcpUrl)
    bridge.start()
}

class StdioBridge(private val ktorMcpUrl: String) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
        
    private val executor = Executors.newSingleThreadExecutor()
    
    @Volatile
    private var sessionUrl: String? = null
    
    @Volatile
    private var sseConnected = false
    
    @Volatile
    private var cachedToolsResponse: String? = null
    
    fun start() {
        executor.submit {
            runSseListener()
        }
        
        System.err.println("✅ TestBed Core MCP Stdio Bridge (Kotlin/JVM) Started.")
        System.err.flush()
        
        val reader = BufferedReader(InputStreamReader(System.`in`))
        var line: String? = reader.readLine()
        while (line != null) {
            line = line.trim()
            if (line.isNotEmpty()) {
                handleInputLine(line)
            }
            line = reader.readLine()
        }
    }
    
    private fun runSseListener() {
        while (true) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(ktorMcpUrl))
                    .GET()
                    .build()
                    
                val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
                sseConnected = false
                
                response.body().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val dataContent = trimmed.substring(5).trim()
                        if (!sseConnected && (dataContent.contains("sessionId=") || dataContent.contains("session_id="))) {
                            val baseUrl = ktorMcpUrl.substringBefore("/mcp")
                            sessionUrl = "$baseUrl$dataContent"
                            sseConnected = true
                            System.err.println("✅ Stdio Bridge: SSE Session Established. Endpoint=$sessionUrl")
                            
                            fetchAndCacheTools()
                        } else {
                            if (dataContent.contains("jsonrpc")) {
                                if (dataContent.contains(""""id"\s*:\s*999""".toRegex()) || dataContent.contains(""""id":999""")) {
                                    cachedToolsResponse = dataContent
                                    System.err.println("✅ Stdio Bridge: Dynamic tools catalog cached from SSE channel.")
                                } else {
                                    println(dataContent)
                                    System.out.flush()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                sseConnected = false
                sessionUrl = null
                System.err.println("⚠️ Stdio Bridge: Connection lost: ${e.message}. Retrying in 2 seconds...")
                Thread.sleep(2000)
            }
        }
    }
    
    private fun fetchAndCacheTools() {
        val url = sessionUrl ?: return
        try {
            val payload = """{"jsonrpc":"2.0","method":"tools/list","id":999}"""
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
                
            client.send(request, HttpResponse.BodyHandlers.discarding())
            System.err.println("✅ Stdio Bridge: Requested tools/list for caching.")
        } catch (e: Exception) {
            System.err.println("⚠️ Stdio Bridge: Failed to trigger tools/list request: ${e.message}")
        }
    }
    
    private fun handleInputLine(line: String) {
        try {
            val reqId = extractJsonValue(line, "id")
            val method = extractJsonValue(line, "method")
            
            if (reqId == null) {
                return
            }
            
            if (method == "initialize") {
                val resp = """{"jsonrpc":"2.0","result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"testbed-core","version":"1.0.0"}},"id":$reqId}"""
                println(resp)
                System.out.flush()
                return
            }
            
            if (method == "tools/list") {
                var waitCount = 30
                while (cachedToolsResponse == null && waitCount > 0) {
                    Thread.sleep(100)
                    waitCount--
                }
                
                val cached = cachedToolsResponse
                if (cached != null) {
                    val replaced = cached.replace(Regex(""""id"\s*:\s*999"""), """"id":$reqId""")
                    println(replaced)
                    System.out.flush()
                } else {
                    val err = """{"jsonrpc":"2.0","error":{"code":-32603,"message":"Error: TestBed Core backend Ktor server is unreachable."},"id":$reqId}"""
                    println(err)
                    System.out.flush()
                }
                return
            }
            
            forwardRequest(line, reqId)
            
        } catch (e: Exception) {
            System.err.println("Error processing line: ${e.message}")
        }
    }
    
    private fun forwardRequest(line: String, reqId: String) {
        var waitCount = 50
        while (!sseConnected && waitCount > 0) {
            Thread.sleep(100)
            waitCount--
        }
        
        val url = sessionUrl
        if (url == null) {
            val err = """{"jsonrpc":"2.0","error":{"code":-32603,"message":"Error: TestBed Core backend Ktor server is unreachable."},"id":$reqId}"""
            println(err)
            System.out.flush()
            return
        }
        
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(line))
                .timeout(Duration.ofSeconds(120))
                .build()
                
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                println(response.body())
                System.out.flush()
            }
        } catch (e: Exception) {
            val err = """{"jsonrpc":"2.0","error":{"code":-32603,"message":"Error forwarding request: ${e.message}"},"id":$reqId}"""
            println(err)
            System.out.flush()
        }
    }
    
    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*(?:"([^"]+)"|([0-9]+)|true|false)""")
        val match = pattern.find(json) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value ?: match.value.substringAfter(":").trim()
    }
}
