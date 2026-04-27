package org.example.project.junit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.LogLevel
import org.example.project.LogLine
import org.example.project.TestPlugin
import org.example.project.JUnitBridge
import org.example.project.TestLogLevel
import org.example.project.adb.LogEvent
import org.example.project.junit.xmlreport.AntXmlRunListener
import com.android.certifications.junit.UnitTestingTextListener
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.time.LocalTime

class JUnitTestExecutor(private val baseDir: File) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _currentTestStep = MutableStateFlow("")
    val currentTestStep = _currentTestStep.asStateFlow()

    private val _currentTestProgress = MutableStateFlow(0)
    val currentTestProgress = _currentTestProgress.asStateFlow()

    private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()

    // For MCP
    val mcpTestResults = java.util.concurrent.CopyOnWriteArrayList<org.example.project.mcp.McpTestResult>()
    val mcpTestLogs = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()

    init {
        JUnitBridge.logging = { message, level ->
            val internalLevel = when (level) {
                TestLogLevel.DEBUG -> LogLevel.DEBUG
                TestLogLevel.INFO -> LogLevel.INFO
                TestLogLevel.PASS -> LogLevel.PASS
                TestLogLevel.WARN -> LogLevel.WARN
                TestLogLevel.ERROR -> LogLevel.ERROR
            }
            log("PLUGIN", message, internalLevel)
        }
        JUnitBridge.onProgress = { step, percent ->
            _currentTestStep.value = step
            _currentTestProgress.value = percent
        }

        JUnitBridge.resourceDir = File(baseDir, "resources").absolutePath
        JUnitBridge.configFilePath = File(baseDir, "config/settings.json").absolutePath
        JUnitBridge.resultsDir = File(baseDir, "results").absolutePath
        JUnitBridge.baseDir = baseDir.absolutePath
    }

    private fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        _logs.tryEmit(LogEvent(tag, message, level))
        if (_isRunning.value) {
            val timestamp = LocalTime.now().toString().take(8)
            mcpTestLogs.add(mapOf("time" to timestamp, "level" to level.name, "message" to "[$tag] $message"))
        }
    }

    fun logging(message: String) {
        val level = when {
            message.contains("failed") || message.contains("Exception") -> LogLevel.ERROR
            message.contains("[SKIPPED REASON]") || message.contains("skipped") -> LogLevel.WARN
            message.contains("passed") -> LogLevel.PASS
            else -> LogLevel.DEBUG
        }
        log("TEST", message, level)
    }

    private fun output_path(): String {
        val dir = File(baseDir, "results").apply { mkdirs() }
        return dir.absolutePath
    }

    fun runTest(plugin: TestPlugin, methodName: String? = null, isMcp: Boolean = false) {
        if (_isRunning.value) {
            if (isMcp) {
                mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", "Another test is already running", null))
            }
            return
        }

        if (isMcp) {
            mcpTestResults.clear()
            mcpTestLogs.clear()
        }
        _currentTestStep.value = "Starting Test"
        _currentTestProgress.value = 0

        scope.launch(Dispatchers.IO) {
            val resultsDir = File(output_path())
            val lockFile = File(resultsDir, "${plugin.shortName}.lock")

            // 重複起動チェック
            if (lockFile.exists()) {
                val lastModified = lockFile.lastModified()
                val now = System.currentTimeMillis()
                val diffMinutes = (now - lastModified) / (1000 * 60)
                if (diffMinutes >= 10) {
                    log("TEST", "Stale lock file found for ${plugin.shortName}, deleting.", LogLevel.WARN)
                    lockFile.delete()
                } else {
                    log("TEST", "Test ${plugin.shortName} is already running (lock file exists). Aborting.", LogLevel.WARN)
                    if (isMcp) {
                        mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", "Test is already running (lock file exists)", null))
                    }
                    return@launch
                }
            }

            _isRunning.value = true
            log("TEST", "START: ${plugin.name}${if(methodName != null) "#$methodName" else ""}", LogLevel.INFO)

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

            // ロックファイルの作成
            try {
                lockFile.writeText(timestamp)
            } catch (e: Exception) {
                log("TEST", "Failed to create lock file: ${e.message}", LogLevel.ERROR)
                if (isMcp) {
                    mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", "Failed to create lock file: ${e.message}", null))
                }
                _isRunning.value = false
                return@launch
            }

            val props = Properties().apply {
                setProperty("SFR.shortname", plugin.shortName)
            }

            var fos: FileOutputStream? = null
            
            // For MCP output capture
            val originalOut = System.out
            val originalErr = System.err
            val outCapture = java.io.ByteArrayOutputStream()
            val errCapture = java.io.ByteArrayOutputStream()
            
            class TeeStream(val main: java.io.OutputStream, val branch: java.io.OutputStream) : java.io.OutputStream() {
                override fun write(b: Int) { main.write(b); branch.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { main.write(b, off, len); branch.write(b, off, len) }
                override fun flush() { main.flush(); branch.flush() }
            }
            
            val outPrintStream = java.io.PrintStream(TeeStream(originalOut, outCapture))
            val errPrintStream = java.io.PrintStream(TeeStream(originalErr, errCapture))

            try {
                val antRunner = AntXmlRunListener(::logging, props) {
                    scope.launch {
                        _isRunning.value = false
                        log("TEST", "FINISH: ${plugin.name}${if(methodName != null) "#$methodName" else ""}", LogLevel.PASS)
                    }
                }

                val reportFile = File(resultsDir, "junit-report-${plugin.shortName}-$timestamp.xml")
                fos = FileOutputStream(reportFile)
                antRunner.setOutputStream(fos)

                val originalClassLoader = Thread.currentThread().contextClassLoader
                val targetClass = plugin.resolveClass()

                Thread.currentThread().contextClassLoader = targetClass.classLoader
                
                if (isMcp) {
                    System.setOut(outPrintStream)
                    System.setErr(errPrintStream)
                }
                
                try {
                    val runner = JUnitTestRunner(arrayOf(targetClass), antRunner)
                    if (methodName != null) {
                        runner.methodNameToRun = methodName
                    }
                    
                    if (isMcp) {
                        runner.addListener(object : org.junit.runner.notification.RunListener() {
                            override fun testFinished(description: org.junit.runner.Description) {
                                if (mcpTestResults.none { it.method_name == description.methodName }) {
                                    mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", description.methodName, "Pass"))
                                }
                            }
                            override fun testFailure(failure: org.junit.runner.notification.Failure) {
                                val assertionMsg = failure.message
                                val stacktrace = failure.trace
                                mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", failure.description.methodName, "Fail", assertionMsg, stacktrace))
                            }
                        })
                    }
                    
                    runner.addListener(UnitTestingTextListener(::logging){})
                    runner.run()
                } finally {
                    if (isMcp) {
                        outPrintStream.flush()
                        errPrintStream.flush()
                        System.setOut(originalOut)
                        System.setErr(originalErr)
                    }
                    Thread.currentThread().contextClassLoader = originalClassLoader
                }
                
                if (isMcp) {
                    antRunner.setSystemOutput(outCapture.toString("UTF-8"))
                    antRunner.setSystemError(errCapture.toString("UTF-8"))
                }

                fos.flush()
            } catch (e: Exception) {
                log("TEST", "ERROR: ${e.message}", LogLevel.ERROR)
                if (isMcp) {
                    mcpTestResults.add(org.example.project.mcp.McpTestResult(plugin.className ?: "", methodName ?: "Unknown", "Error", e.message, e.stackTraceToString()))
                }
                _isRunning.value = false
            } finally {
                try { fos?.close() } catch (e: Exception) {}
                
                // XMLパッチのマージ (遅延書き出しを待機)
                scope.launch(Dispatchers.IO) {
                    val patchFile = File(resultsDir, "xml-patches/PATCH-junit-report-${plugin.shortName}-$timestamp.xml")
                    val reportFile = File(resultsDir, "junit-report-${plugin.shortName}-$timestamp.xml")
                    
                    // パッチファイルの存在を待機 (最大2秒)
                    var retry = 0
                    while (!patchFile.exists() && retry < 20) {
                        delay(100)
                        retry++
                    }
                    
                    if (patchFile.exists() && reportFile.exists()) {
                        // ファイル書き出しの完了を少し待つ
                        delay(200)
                        org.example.project.tools.XmlMerger.merge(reportFile, patchFile)
                        // パッチ適用後に削除
                        patchFile.delete()
                        // HTMLレポートの生成
                        generateHtmlReport(reportFile)
                    } else {
                        log("TEST", "Merge skipped: files not found (Patch: ${patchFile.exists()}, Report: ${reportFile.exists()})", LogLevel.WARN)
                        // パッチがなくてもレポートがあればHTML生成を試みる
                        if (reportFile.exists()) {
                            generateHtmlReport(reportFile)
                        }
                    }
                    
                    // ロックファイルの削除
                    if (lockFile.exists()) {
                        lockFile.delete()
                    }
                }
            }
        }
    }

    private fun generateHtmlReport(xmlFile: File) {
        try {
            val resourcesDir = File(baseDir, "resources")
            val xsltFile = File(resourcesDir, "summary.xslt")

            if (!xsltFile.exists()) {
                log("TEST", "summary.xslt not found in resources directory. Extracting from JAR...", LogLevel.INFO)
                resourcesDir.mkdirs()
                
                val xsltInputStream = JUnitTestExecutor::class.java.getResourceAsStream("/summary.xslt") 
                    ?: JUnitTestExecutor::class.java.classLoader.getResourceAsStream("summary.xslt")
                
                if (xsltInputStream == null) {
                    log("TEST", "summary.xslt not found in JAR resources", LogLevel.ERROR)
                    return
                }
                
                try {
                    xsltInputStream.use { input ->
                        xsltFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    log("TEST", "summary.xslt extracted to ${xsltFile.absolutePath}", LogLevel.INFO)
                } catch (e: Exception) {
                    log("TEST", "Failed to extract summary.xslt: ${e.message}", LogLevel.ERROR)
                    return
                }
            }
            
            val factory = javax.xml.transform.TransformerFactory.newInstance()
            val transformer = factory.newTransformer(javax.xml.transform.stream.StreamSource(xsltFile))
            
            val htmlFile = File(xmlFile.parentFile, xmlFile.name.replace(".xml", ".html"))
            
            transformer.transform(
                javax.xml.transform.stream.StreamSource(xmlFile),
                javax.xml.transform.stream.StreamResult(htmlFile)
            )
            log("TEST", "HTML report generated: ${htmlFile.absolutePath}", LogLevel.INFO)
        } catch (e: Exception) {
            log("TEST", "Failed to generate HTML report: ${e.message}", LogLevel.ERROR)
        }
    }
}
