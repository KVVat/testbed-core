import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.sse.*
import kotlinx.coroutines.runBlocking
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation

fun main() = runBlocking {
    val client = HttpClient(CIO) {
        install(SSE)
    }

    val transport = SseClientTransport(client, "http://localhost:11452/mcp")
    val mcpClient = Client(Implementation(name = "test-client", version = "1.0.0"))

    try {
        mcpClient.connect(transport)
        
        println("Connected to MCP Server!")
        
        val tools = mcpClient.listTools()
        println("Available Tools:")
        tools.tools.forEach {
            println(" - ${it.name}: ${it.description}")
        }

        println("\nAttempting to call get_device_info...")
        val result = mcpClient.callTool("get_device_info")
        println("Result: ${result.content}")

    } catch (e: Exception) {
        println("Error during MCP communication: ${e.message}")
        e.printStackTrace()
    } finally {
        mcpClient.close()
        client.close()
    }
}
