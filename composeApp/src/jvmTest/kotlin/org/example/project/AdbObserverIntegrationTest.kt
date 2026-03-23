package org.example.project

import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 実際に立ち上がっているバックグラウンドのプロセス (ポート11452) に対して、
 * mcp_call.sh 経由でリクエストを投げてE2Eテストを実行します。
 * 
 * E2EでJSONのエンコード/デコードやKtorのルーティングも含めた完全な疎通確認が可能です。
 */
class McpSseServerE2ETest {

    private fun runMcpCall(toolName: String, argsJson: String = ""): String {
        // Gradle test runs in either root or subproject dir. Resolve root correctly.
        var rootDir = File(System.getProperty("user.dir"))
        if (rootDir.name == "composeApp") {
            rootDir = rootDir.parentFile
        }
        val scriptFile = File(rootDir, "scripts/mcp_call.sh")
        
        val command = if (argsJson.isNotEmpty()) {
            arrayOf(scriptFile.absolutePath, toolName, argsJson)
        } else {
            arrayOf(scriptFile.absolutePath, toolName)
        }
        
        val process = ProcessBuilder(*command)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }

    private fun checkServerRunning() {
        // ping等の簡単なコマンドでサーバーが起動しているか確認
        val output = runMcpCall("ping")
        Assume.assumeTrue("MCP Server is not reachable at localhost:11452. Output: $output", output.contains("pong") || output.contains("result"))
    }

    @Test
    fun testGetDeviceInfo() {
        checkServerRunning()
        val output = runMcpCall("get_device_info")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field. Output: $output")
        assertTrue(!output.contains("Error:"), "Response should not be an Error. Output: $output")
        println("get_device_info success: $output")
    }

    @Test
    fun testClearLogcat() {
        checkServerRunning()
        val output = runMcpCall("clear_logcat")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        println("clear_logcat success")
    }

    @Test
    fun testGetLogcat() {
        checkServerRunning()
        val output = runMcpCall("get_logcat", "{\"max_lines\": 5}")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        println("get_logcat success")
    }

    @Test
    fun testUiDumpWithoutImage() {
        checkServerRunning()
        val output = runMcpCall("get_ui_dump", "{\"include_image\": false}")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        assertTrue(output.contains("dump_result"), "Result should contain dump_result. Output: $output")
        assertTrue(!output.contains("\"screenshot\""), "Should NOT contain screenshot field when requested without image.")
        println("get_ui_dump success")
    }

    @Test
    fun testUiDumpWithImage() {
        checkServerRunning()
        val output = runMcpCall("get_ui_dump", "{\"include_image\": true}")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        assertTrue(output.contains("dump_result"), "Result should contain dump_result. Output: $output")
        assertTrue(output.contains("\"screenshot\""), "Result should contain screenshot field. Output size: ${output.length}")
        assertTrue(output.length > 5000, "With image, output size should be much larger")
        println("get_ui_dump with image success")
    }

    @Test
    fun testSwipe() {
        checkServerRunning()
        // 中央付近をスワイプ
        val output = runMcpCall("swipe", "{\"start_x\": 500, \"start_y\": 1000, \"end_x\": 500, \"end_y\": 500}")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        assertTrue(output.contains("dump_result"), "Swipe should return updated UI dump.")
        println("swipe success")
    }

    @Test
    fun testTap() {
        checkServerRunning()
        val output = runMcpCall("tap", "{\"x\": 500, \"y\": 500}")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        assertTrue(output.contains("dump_result"), "Tap should return updated UI dump.")
        println("tap success")
    }

    @Test
    fun testInputText() {
        checkServerRunning()
        val output = runMcpCall("input_text", "{\"text\": \"e2etest\"}")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        assertTrue(output.contains("dump_result"), "Input text should return updated UI dump.")
        println("input_text success")
    }

    @Test
    fun testPressKey() {
        checkServerRunning()
        val output = runMcpCall("press_key", "{\"keycode\": \"HOME\"}")
        assertTrue(output.contains("\"result\""), "Response should contain JSON result field.")
        assertTrue(output.contains("dump_result"), "Press key should return updated UI dump.")
        println("press_key success")
    }
}
