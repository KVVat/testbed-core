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
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.example.project.adb.AdbObserver

class McpSseServer(private val adbObserver: AdbObserver) {

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
            name = "dump",
            description = "Dump UI hierarchy and screenshot from the mutton agent"
        ) { _ ->
            adbObserver.dumpMuttonAgent()
            CallToolResult(content = listOf(TextContent("Dump requested")))
        }

        mcpServer.addTool(
            name = "get_device_info",
            description = "端末のハードウェア・OS情報を取得します（getprop のラッパー）"
        ) { _ ->
            val info = adbObserver.getDeviceInfo()
            CallToolResult(content = listOf(TextContent(info)))
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
            
            routing {
                sse("/mcp") {
                    val transport = SseServerTransport("/mcp/message", this)
                    val serverSession = mcpServer.createSession(transport)
                    serverSessions[transport.sessionId] = serverSession

                    serverSession.onClose {
                        println("Server session closed for: ${transport.sessionId}")
                        serverSessions.remove(transport.sessionId)
                    }
                    awaitCancellation()
                }
                
                post("/mcp/message") {
                    val sessionId: String? = call.request.queryParameters["sessionId"]
                    if (sessionId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing sessionId parameter")
                        return@post
                    }

                    val transport = serverSessions[sessionId]?.transport as? SseServerTransport
                    if (transport == null) {
                        call.respond(HttpStatusCode.NotFound, "Session not found")
                        return@post
                    }

                    transport.handlePostMessage(call)
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
