package org.example.mutton
import android.app.Instrumentation
import android.util.Log
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.mutton.uidumper.JsonUiDumper
import org.json.JSONObject
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android Instrumentation Test として起動されるエントリポイント
 * ソケットサーバーとして常駐し、JSONコマンドを処理します。
 */
@RunWith(AndroidJUnit4::class)
class AgentTest {

    private val SOCKET_NAME = "mutton_agent"

    private lateinit var instrumentation: Instrumentation
    private lateinit var device: UiDevice

    private val serverScope = CoroutineScope(Dispatchers.IO)
    private val deviceMutex = Mutex()

    @Test
    fun startServer() {

       
        // 2. ソケットサーバーの立ち上げ
        // AdbObserver側で "mutton_agent" という名前の Abstract Socket に転送しているため
        // ここでも同じ名前で待ち受ける必要があります。
        // LocalServerSocket(name) はデフォルトで Linux Abstract Namespace にソケットを作ります。
        try {
            val server = LocalServerSocket(SOCKET_NAME)
            Log.i("MuttonAgent", "Listening on localabstract:$SOCKET_NAME")
            instrumentation = InstrumentationRegistry.getInstrumentation()
            device = UiDevice.getInstance(instrumentation)

            // 接続待ちループ
            while (true) {
                try {
                    // クライアントからの接続を待機 (ブロッキング)
                    val client = server.accept()
                    serverScope.launch {
                        handleClient(client)
                    }

                } catch (e: Exception) {
                    Log.i("MuttonAgent", "Connection error: ${e.message}")
                    e.printStackTrace()
                    // 致命的なエラーでない限りループを継続
                }
            }
        } catch (e: Exception) {
            Log.e("MuttonAgent", "Fatal error: ${e.message}")
            e.printStackTrace()
        }

        Log.i("MuttonAgent", ">>> AGENT_STOPPED")
    }

    private suspend fun handleClient(client: LocalSocket) {
        // useブロックで自動的にクローズ
        client.use { socket ->
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val writer = PrintWriter(socket.outputStream, true)

                // 行単位でコマンドを受信
                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.isBlank()) {
                        line = reader.readLine()
                        continue
                    }

                   
                    val response = try {
                        val cmdJson = JSONObject(line)
                        processCommand(cmdJson, writer)
                    } catch (e: Exception) {
                        createError("Invalid JSON: ${e.message}")
                    }

                    // 応答を送信
                    writer.println(response.toString())

                    // 次の行を読む
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.e("MuttonAgent", "Error in handleClient processing: ${e.message}", e)
            } finally {
                // Cleanups for active streams are no longer needed
            }
        }
    }

    private suspend fun processCommand(json: JSONObject, writer: PrintWriter): JSONObject {
        val cmd = json.optString("cmd")
        Log.i("MuttonAgent", "Processing command: $cmd")
        return when (cmd) {
            "ping" -> {
                JSONObject().put("status", "pong").put("message", "I am alive!")
            }
            "version" -> {
                val versionStr = "${BuildConfig.VERSION_NAME}(${BuildConfig.BUILD_TIME})"
                Log.i("MuttonAgent", "Requested Version: $versionStr")
                JSONObject().put("status", "ok").put("version", versionStr)
            }
            "get_ui_dump" -> {
                deviceMutex.withLock {
                    val includeImage = json.optBoolean("include_image", false)
                    val qualityLevel = json.optInt("image_quality", 2)
                    
                    try {
                        device.waitForIdle(1000)
                        
                        var base64: String? = null
                        if (includeImage) {
                            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                            if (bitmap != null) {
                                base64 = compressBitmap(bitmap, qualityLevel)
                            }
                        }

                        val activeNode = instrumentation.uiAutomation.rootInActiveWindow
                        if (activeNode != null) {
                            val rootNode = JsonUiDumper().dumpNodeRec(activeNode, 0)
                            val jsonResponse = JSONObject()
                                .put("type", "dump_result")
                                .put("status", "ok")
                                .put("output", Json.encodeToString(rootNode))
                                .put("screen_width", device.displayWidth)
                                .put("screen_height", device.displayHeight)
                            if (base64 != null) {
                                jsonResponse.put("screenshot", base64)
                            }
                            jsonResponse
                        } else {
                            val jsonResponse = JSONObject()
                                .put("type", "dump_result")
                                .put("status", "ng")
                                .put("message", "instrumentation.uiAutomation.rootInActiveWindow returned null. Active window might not be accessible.")
                            if (base64 != null) {
                                jsonResponse.put("screenshot", base64)
                            }
                            jsonResponse
                        }
                    } catch (e: Exception) {
                        JSONObject()
                            .put("type", "dump_result")
                            .put("status", "error")
                            .put("message", "Exception during dump: ${e.message}")
                    }
                }
            }
            "tap" -> {
                deviceMutex.withLock {
                    val x = json.optInt("x", 0)
                    val y = json.optInt("y", 0)
                    Log.i("MuttonAgent", "Tapping at ($x, $y)")
                    val success = device.click(x, y)
                    if (success) {
                        device.waitForIdle(1000)
                        JSONObject().put("status", "ok")
                    } else {
                        createError("Failed to tap at ($x, $y)")
                    }
                }
            }
            "swipe" -> {
                deviceMutex.withLock {
                    val startX = json.optInt("start_x", 0)
                    val startY = json.optInt("start_y", 0)
                    val endX = json.optInt("end_x", 0)
                    val endY = json.optInt("end_y", 0)
                    Log.i("MuttonAgent", "Swiping from ($startX, $startY) to ($endX, $endY)")
                    val success = device.swipe(startX, startY, endX, endY, 50)
                    if (success) {
                        device.waitForIdle(1000)
                        JSONObject().put("status", "ok")
                    } else {
                        createError("Failed to swipe")
                    }
                }
            }
            "input_text" -> {
                deviceMutex.withLock {
                    val text = json.optString("text", "")
                    val pressEnter = json.optBoolean("press_enter", true)
                    Log.i("MuttonAgent", "Inputting text: $text")
                    if (text.isNotEmpty()) {
                        val process = Runtime.getRuntime().exec(arrayOf("input", "text", text))
                        process.waitFor()
                    }
                    if (pressEnter) {
                        device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
                    }
                    device.waitForIdle(1000)
                    JSONObject().put("status", "ok")
                }
            }
            "press_key" -> {
                deviceMutex.withLock {
                    val keycodeStr = json.optString("keycode", "")
                    Log.i("MuttonAgent", "Pressing key: $keycodeStr")
                    if (keycodeStr.isNotEmpty()) {
                        val codeStr = if (keycodeStr.startsWith("KEYCODE_")) keycodeStr else "KEYCODE_$keycodeStr"
                        val code = android.view.KeyEvent.keyCodeFromString(codeStr)
                        if (code != android.view.KeyEvent.KEYCODE_UNKNOWN) {
                            device.pressKeyCode(code)
                        } else {
                            val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keycodeStr))
                            process.waitFor()
                        }
                        device.waitForIdle(1000)
                        JSONObject().put("status", "ok")
                    } else {
                        createError("Empty keycode")
                    }
                }
            }
            "shell" -> {
                val commandStr = json.getString("args")
                Log.i("MuttonAgent", "Executing shell command: $commandStr")
                
                withContext(Dispatchers.IO) {
                    val resultJson = withTimeoutOrNull(8000) {
                        var process: Process? = null
                        try {
                            process = Runtime.getRuntime().exec(commandStr)
                            val output = process.inputStream.bufferedReader().use { it.readText() }
                            val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                            val exitVal = process.waitFor()
                            
                            if (exitVal == 0) {
                                JSONObject().put("status", "ok").put("output", output)
                            } else {
                                JSONObject().put("status", "failed")
                                    .put("exit_code", exitVal)
                                    .put("output", output)
                                    .put("error", errorOutput)
                            }
                        } catch (e: Exception) {
                            JSONObject().put("status", "error").put("message", e.message ?: "Execution failed")
                        } finally {
                            process?.destroy()
                        }
                    }

                    resultJson ?: JSONObject()
                        .put("status", "timeout")
                        .put("message", "Command timed out on device after 8 seconds.")
                }
            }
            "exit" -> {
                System.exit(0)
                JSONObject().put("status", "exiting")
            }
            else -> createError("Unknown command: $cmd")
        }
    }

    private fun createError(msg: String): JSONObject {
        return JSONObject().put("status", "error").put("message", msg)
    }

    private fun compressBitmap(bitmap: android.graphics.Bitmap, qualityLevel: Int): String {
        val (jpegQuality, scaleDivisor) = when (qualityLevel) {
            1 -> Pair(80, 1) // 原寸, 高画質
            2 -> Pair(50, 2) // 1/2サイズ, 中画質
            3 -> Pair(33, 3) // 1/3サイズ, ギリギリ読める低画質
            4 -> Pair(20, 4) // 1/4サイズ, 超低画質
            else -> Pair(50, 2)
        }

        var finalBitmap = bitmap
        if (scaleDivisor > 1) {
            finalBitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                bitmap.width / scaleDivisor,
                bitmap.height / scaleDivisor,
                true
            )
        }

        val stream = java.io.ByteArrayOutputStream()
        finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, stream)
        val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)

        // Only recycle if we created a new scaled bitmap
        if (finalBitmap != bitmap) {
            finalBitmap.recycle()
        }
        bitmap.recycle() // Recycle original screenshot bitmap

        return base64
    }
}