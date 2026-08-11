package org.example.project.python

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.JUnitBridge
import org.example.project.LogLevel
import org.example.project.TestPlugin
import org.example.project.adb.AdbRepository
import org.example.project.adb.LogEvent
import org.example.project.junit.xmlreport.DOMElementWriter
import org.example.project.junit.xmlreport.DateUtils
import org.example.project.mcp.McpTestResult
import org.example.project.tools.XmlMerger
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

private data class PyCaseSummary(
    val name: String = "",
    val classname: String = "",
    val time: String = "0.000",
    val status: String = "Pass",
    val message: String = ""
)

private data class PyExecutionSummary(
    val tests_run: Int = 0,
    val failures: Int = 0,
    val errors: Int = 0,
    val time: String = "0.000",
    val cases: List<PyCaseSummary> = emptyList()
)

class PythonTestExecutor(
    private val baseDir: File,
    private val adbRepository: AdbRepository? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _currentTestStep = MutableStateFlow("")
    val currentTestStep = _currentTestStep.asStateFlow()

    private val _currentTestProgress = MutableStateFlow(0)
    val currentTestProgress = _currentTestProgress.asStateFlow()

    private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()

    val mcpTestResults = java.util.concurrent.CopyOnWriteArrayList<McpTestResult>()
    val mcpTestLogs = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()

    private val systemOutBuffer = mutableListOf<String>()
    private val systemErrBuffer = mutableListOf<String>()

    private fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        _logs.tryEmit(LogEvent(tag, message, level))
        val timestamp = LocalTime.now().toString().take(8)
        val formattedLog = "[$timestamp] [$level] [$tag] $message"
        synchronized(systemOutBuffer) {
            systemOutBuffer.add(formattedLog)
        }
        if (_isRunning.value) {
            mcpTestLogs.add(mapOf("time" to timestamp, "level" to level.name, "message" to "[$tag] $message"))
        }
    }

    private fun resultsDir(): File {
        return File(baseDir, "results").apply { mkdirs() }
    }

    private val hostname: String
        get() = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "localhost"
        }

    fun runTest(plugin: TestPlugin, methodName: String? = null, isMcp: Boolean = false) {
        if (_isRunning.value) {
            if (isMcp) {
                mcpTestResults.add(McpTestResult(plugin.shortName, methodName ?: "all", "Error", "Another test is already running", null))
            }
            return
        }

        val scriptFile = plugin.scriptFile
        if (scriptFile == null || !scriptFile.exists()) {
            log("TEST", "Python script not found: ${scriptFile?.absolutePath}", LogLevel.ERROR)
            if (isMcp) {
                mcpTestResults.add(McpTestResult(plugin.shortName, methodName ?: "all", "Error", "Python script not found", null))
            }
            return
        }

        if (isMcp) {
            mcpTestResults.clear()
            mcpTestLogs.clear()
        }

        synchronized(systemOutBuffer) {
            systemOutBuffer.clear()
        }
        synchronized(systemErrBuffer) {
            systemErrBuffer.clear()
        }

        _isRunning.value = true
        _currentTestStep.value = "Starting Python Test"
        _currentTestProgress.value = 0

        scope.launch(Dispatchers.IO) {
            val resultsFolder = resultsDir()
            val lockFile = File(resultsFolder, "${plugin.shortName}.lock")

            if (lockFile.exists()) {
                val lastModified = lockFile.lastModified()
                val diffMinutes = (System.currentTimeMillis() - lastModified) / (1000 * 60)
                if (diffMinutes >= 10) {
                    log("TEST", "Stale lock file found for ${plugin.shortName}, deleting.", LogLevel.WARN)
                    lockFile.delete()
                } else {
                    log("TEST", "Test ${plugin.shortName} is already running. Aborting.", LogLevel.WARN)
                    if (isMcp) {
                        mcpTestResults.add(McpTestResult(plugin.shortName, methodName ?: "all", "Error", "Lock file exists", null))
                    }
                    _isRunning.value = false
                    return@launch
                }
            }

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val isoTimestamp = DateUtils.format(Date(), DateUtils.ISO8601_DATETIME_PATTERN)

            try {
                lockFile.writeText(timestamp)
            } catch (e: Exception) {
                log("TEST", "Failed to create lock file: ${e.message}", LogLevel.ERROR)
                _isRunning.value = false
                return@launch
            }

            val reportFile = File(resultsFolder, "junit-report-${plugin.shortName}-$timestamp.xml")

            // 1. Retrieve system & device info
            var deviceModel = ""
            var osVersion = ""
            var buildDisplayId = ""
            var deviceSerial = adbRepository?.adbState?.value?.deviceSerial ?: (System.getProperty("DEVICE_SERIAL") ?: "")

            if (deviceSerial.isBlank()) {
                try {
                    val p = ProcessBuilder("adb", "devices").start()
                    val lines = p.inputStream.bufferedReader().readLines()
                    val devLine = lines.drop(1).firstOrNull { it.contains("\tdevice") }
                    if (devLine != null) {
                        deviceSerial = devLine.split("\t")[0].trim()
                    }
                } catch (_: Exception) {}
            }
            if (deviceSerial.isBlank()) {
                try {
                    val p = ProcessBuilder("adb", "get-serialno").start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    if (out.isNotBlank() && out != "unknown" && !out.startsWith("error")) {
                        deviceSerial = out
                    }
                } catch (_: Exception) {}
            }

            if (deviceSerial.isNotBlank()) {
                if (adbRepository != null && adbRepository.adbState.value.isValid) {
                    try {
                        deviceModel = adbRepository.executeAdbShell("getprop ro.product.model").trim()
                        osVersion = adbRepository.executeAdbShell("getprop ro.build.version.release").trim()
                        buildDisplayId = adbRepository.executeAdbShell("getprop ro.build.display.id").trim()
                    } catch (_: Exception) {}
                }
                if (deviceModel.isBlank()) {
                    try {
                        val p = ProcessBuilder("adb", "-s", deviceSerial, "shell", "getprop", "ro.product.model").start()
                        val out = p.inputStream.bufferedReader().readText().trim()
                        if (out.isNotBlank() && !out.contains("error", ignoreCase = true)) deviceModel = out
                    } catch (_: Exception) {}
                }
                if (osVersion.isBlank()) {
                    try {
                        val p = ProcessBuilder("adb", "-s", deviceSerial, "shell", "getprop", "ro.build.version.release").start()
                        val out = p.inputStream.bufferedReader().readText().trim()
                        if (out.isNotBlank() && !out.contains("error", ignoreCase = true)) osVersion = out
                    } catch (_: Exception) {}
                }
                if (buildDisplayId.isBlank()) {
                    try {
                        val p = ProcessBuilder("adb", "-s", deviceSerial, "shell", "getprop", "ro.build.display.id").start()
                        val out = p.inputStream.bufferedReader().readText().trim()
                        if (out.isNotBlank() && !out.contains("error", ignoreCase = true)) buildDisplayId = out
                    } catch (_: Exception) {}
                }
            }

            val testTargetName = "${plugin.name}${if (methodName != null) "#$methodName" else ""}"
            log("TEST", "START: $testTargetName", LogLevel.INFO)
            log("TEST", "Target Device: ${deviceSerial.ifBlank { "None (Host)" }} (Model: ${deviceModel.ifBlank { "N/A" }}, OS: ${osVersion.ifBlank { "N/A" }})", LogLevel.INFO)

            try {
                var receivedSummaryJson: String? = null

                // Host Bridge object to expose to Python
                class TestbedHostBridge {
                    fun log(tag: String, message: String, level: String = "INFO") {
                        val parsedLevel = try { LogLevel.valueOf(level.uppercase()) } catch (_: Exception) { LogLevel.INFO }
                        this@PythonTestExecutor.log(tag, message, parsedLevel)
                    }

                    fun setProgress(step: String, percent: Int) {
                        _currentTestStep.value = step
                        _currentTestProgress.value = percent
                    }

                    fun getResourcePath(relPath: String): String {
                        return File(JUnitBridge.resourceDir, relPath).absolutePath
                    }

                    fun getResultsPath(): String {
                        return resultsFolder.absolutePath
                    }

                    fun getDeviceSerial(): String {
                        return deviceSerial
                    }

                    fun getDeviceModel(): String {
                        return deviceModel
                    }

                    fun getOsVersion(): String {
                        return osVersion
                    }

                    fun isDeviceConnected(): Boolean {
                        return deviceSerial.isNotBlank()
                    }

                    fun executeShell(command: String): String {
                        if (deviceSerial.isBlank()) return "Error: No target device connected."
                        return try {
                            val p = ProcessBuilder("adb", "-s", deviceSerial, "shell", command).redirectErrorStream(true).start()
                            val finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                            if (!finished) {
                                p.destroyForcibly()
                                "Error: ADB shell command timed out: $command"
                            } else {
                                p.inputStream.bufferedReader().readText().trim()
                            }
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                    }

                    fun getProp(propName: String): String {
                        return executeShell("getprop $propName").trim()
                    }

                    fun clearLogcat(): String {
                        log("ADB", "Clearing logcat buffer...", LogLevel.INFO)
                        return executeShell("logcat -c")
                    }

                    fun getLogcat(): String = getLogcat("", 100)
                    fun getLogcat(tag: String): String = getLogcat(tag, 100)
                    fun getLogcat(tag: String, maxLines: Int): String {
                        val tagFilter = if (tag.isNotBlank()) "-s $tag" else ""
                        return executeShell("logcat -d $tagFilter -t $maxLines")
                    }

                    fun waitForLogcat(tag: String, pattern: String): String? = waitForLogcat(tag, pattern, 30)
                    fun waitForLogcat(tag: String, pattern: String, timeoutSec: Int): String? {
                        log("ADB", "Waiting for logcat [tag=$tag, pattern='$pattern'] (timeout: ${timeoutSec}s)...", LogLevel.INFO)
                        val startTime = System.currentTimeMillis()
                        val timeoutMs = timeoutSec * 1000L
                        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { Regex.fromLiteral(pattern) }
                        val tagFilter = if (tag.isNotBlank()) "-s $tag" else ""

                        while (System.currentTimeMillis() - startTime < timeoutMs) {
                            val logs = executeShell("logcat -d $tagFilter -t 200")
                            for (line in logs.lines()) {
                                if (regex.containsMatchIn(line)) {
                                    log("ADB", "Found matching logcat line: $line", LogLevel.PASS)
                                    return line
                                }
                            }
                            Thread.sleep(1000)
                        }
                        log("ADB", "Timed out waiting for logcat [tag=$tag, pattern='$pattern']", LogLevel.WARN)
                        return null
                    }

                    fun reboot(): String = reboot("")
                    fun reboot(mode: String): String {
                        log("ADB", "Rebooting device (mode: '$mode')...", LogLevel.INFO)
                        return try {
                            val cmd = mutableListOf("adb")
                            if (deviceSerial.isNotBlank()) cmd.addAll(listOf("-s", deviceSerial))
                            cmd.add("reboot")
                            if (mode.isNotBlank()) cmd.add(mode)
                            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                            "Reboot signal sent"
                        } catch (e: Exception) {
                            "Error sending reboot: ${e.message}"
                        }
                    }

                    fun waitBoot(): Boolean = waitBoot(180000L)
                    fun waitBoot(timeoutMs: Long): Boolean {
                        log("ADB", "Waiting for device $deviceSerial to boot up (timeout: ${timeoutMs / 1000}s)...", LogLevel.INFO)
                        val startTime = System.currentTimeMillis()
                        Thread.sleep(5000)
                        while (System.currentTimeMillis() - startTime < timeoutMs) {
                            try {
                                val bootCompleted = executeShell("getprop sys.boot_completed").trim()
                                if (bootCompleted == "1") {
                                    val pmCheck = executeShell("pm path android").trim()
                                    if (pmCheck.contains("package:")) {
                                        log("ADB", "Device boot completed and package manager is responsive!", LogLevel.PASS)
                                        return true
                                    }
                                }
                            } catch (_: Exception) {
                            }
                            Thread.sleep(2000)
                        }
                        log("ADB", "Timed out waiting for device boot after ${timeoutMs / 1000}s", LogLevel.ERROR)
                        return false
                    }

                    fun installApk(apkPathOrName: String): String = installApk(apkPathOrName, "-r")

                    fun installApk(apkPathOrName: String, extraArgs: String = "-r"): String {
                        val candidates = listOf(
                            File(apkPathOrName),
                            File(JUnitBridge.resourceDir, apkPathOrName),
                            File(baseDir, "composeApp/resources/$apkPathOrName"),
                            File(baseDir, "resources/$apkPathOrName"),
                            File("composeApp/resources/$apkPathOrName"),
                            File("resources/$apkPathOrName"),
                            File(System.getProperty("user.dir") ?: ".", "composeApp/resources/$apkPathOrName"),
                            File(System.getProperty("user.dir") ?: ".", "resources/$apkPathOrName")
                        )

                        val apkFile = candidates.firstOrNull { it.exists() && it.isFile }

                        if (apkFile == null) {
                            val err = "Error: APK file not found: $apkPathOrName"
                            log("ADB", err, LogLevel.ERROR)
                            return err
                        }

                        log("ADB", "Installing APK: ${apkFile.name} (args: $extraArgs)...", LogLevel.INFO)
                        return try {
                            val cmd = mutableListOf("adb")
                            if (deviceSerial.isNotBlank()) {
                                cmd.addAll(listOf("-s", deviceSerial))
                            }
                            cmd.add("install")
                            if (extraArgs.isNotBlank()) {
                                cmd.addAll(extraArgs.split(" ").filter { it.isNotBlank() })
                            }
                            cmd.add(apkFile.absolutePath)
                            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                            val out = p.inputStream.bufferedReader().readText().trim()
                            p.waitFor()
                            if (out.contains("Success", ignoreCase = true)) {
                                log("ADB", "APK install success: ${apkFile.name}", LogLevel.PASS)
                            } else {
                                log("ADB", "APK install output: $out", LogLevel.WARN)
                            }
                            out
                        } catch (e: Exception) {
                            val err = "Error installing APK: ${e.message}"
                            log("ADB", err, LogLevel.ERROR)
                            err
                        }
                    }

                    fun uninstallApp(packageName: String): String {
                        log("ADB", "Uninstalling package: $packageName...", LogLevel.INFO)
                        return try {
                            val cmd = mutableListOf("adb")
                            if (deviceSerial.isNotBlank()) {
                                cmd.addAll(listOf("-s", deviceSerial))
                            }
                            cmd.addAll(listOf("uninstall", packageName))
                            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                            val out = p.inputStream.bufferedReader().readText().trim()
                            p.waitFor()
                            if (out.contains("Success", ignoreCase = true)) {
                                log("ADB", "Package uninstalled: $packageName", LogLevel.PASS)
                            } else {
                                log("ADB", "Package uninstall output: $out", LogLevel.INFO)
                            }
                            out
                        } catch (e: Exception) {
                            val err = "Error uninstalling package: ${e.message}"
                            log("ADB", err, LogLevel.ERROR)
                            err
                        }
                    }

                    fun isAppInstalled(packageName: String): Boolean {
                        val out = executeShell("pm path $packageName")
                        return out.contains("package:")
                    }

                    fun unlockDevice(): Boolean = unlockDevice("0000")

                    fun unlockDevice(pin: String = "0000"): Boolean {
                        log("ADB", "Unlocking device screen (PIN: $pin)...", LogLevel.INFO)
                        return try {
                            val serial = deviceSerial
                            val prefix = if (serial.isNotBlank()) arrayOf("adb", "-s", serial) else arrayOf("adb")

                            // 1. Wake up screen
                            ProcessBuilder(*prefix, "shell", "input", "keyevent", "KEYCODE_WAKEUP").start().waitFor(3, TimeUnit.SECONDS)
                            Thread.sleep(300)

                            // 2. Dismiss keyguard
                            ProcessBuilder(*prefix, "shell", "wm", "dismiss-keyguard").start().waitFor(3, TimeUnit.SECONDS)
                            Thread.sleep(300)

                            // 3. Unlock with locksettings or input text PIN
                            if (pin.isNotBlank()) {
                                ProcessBuilder(*prefix, "shell", "locksettings", "verify", "--old", pin).start().waitFor(3, TimeUnit.SECONDS)
                                ProcessBuilder(*prefix, "shell", "input", "text", pin).start().waitFor(3, TimeUnit.SECONDS)
                                ProcessBuilder(*prefix, "shell", "input", "keyevent", "KEYCODE_ENTER").start().waitFor(3, TimeUnit.SECONDS)
                            }
                            Thread.sleep(500)

                            val p = ProcessBuilder(*prefix, "shell", "dumpsys", "user").start()
                            p.waitFor(5, TimeUnit.SECONDS)
                            val out = p.inputStream.bufferedReader().readText()
                            val unlocked = out.contains("RUNNING_UNLOCKED")
                            if (unlocked) {
                                log("ADB", "Device successfully unlocked into AFU state", LogLevel.PASS)
                            } else {
                                log("ADB", "Device unlock attempted: $out", LogLevel.INFO)
                            }
                            unlocked
                        } catch (e: Exception) {
                            log("ADB", "Device unlock error: ${e.message}", LogLevel.WARN)
                            false
                        }
                    }

                    fun submitReport(summaryJson: String) {
                        receivedSummaryJson = summaryJson
                    }
                }

                val bridge = TestbedHostBridge()
                val scriptPath = scriptFile.absolutePath.replace("\\", "/")
                val targetMethod = methodName ?: ""

                // Python test harness runner script
                val runnerWrapperScript = """
                    import sys
                    import os
                    import time
                    import json
                    import unittest
                    import importlib.util

                    # Add script directory and resources to sys.path
                    script_dir = os.path.dirname("$scriptPath")
                    if script_dir not in sys.path:
                        sys.path.insert(0, script_dir)

                    # Import the test module
                    spec = importlib.util.spec_from_file_location("${plugin.shortName}", "$scriptPath")
                    module = importlib.util.module_from_spec(spec)
                    module.__dict__["bridge"] = bridge
                    spec.loader.exec_module(module)

                    suite = unittest.TestSuite()
                    loader = unittest.TestLoader()

                    target_method = "$targetMethod".strip()
                    if target_method and target_method != "run_all":
                        for attr_name in dir(module):
                            attr = getattr(module, attr_name)
                            if isinstance(attr, type) and issubclass(attr, unittest.TestCase):
                                if hasattr(attr, target_method):
                                    suite.addTest(attr(target_method))
                    else:
                        suite.addTests(loader.loadTestsFromModule(module))
                        if suite.countTestCases() == 0:
                            standalone_tests = [getattr(module, name) for name in dir(module) if name.startswith("test_") and callable(getattr(module, name))]
                            if standalone_tests:
                                class StandaloneTestCase(unittest.TestCase):
                                    pass
                                for func in standalone_tests:
                                    def make_test_method(f):
                                        return lambda self: f()
                                    setattr(StandaloneTestCase, func.__name__, make_test_method(func))
                                    suite.addTest(StandaloneTestCase(func.__name__))

                    class CaseInfo:
                        def __init__(self, name, classname, duration, status, message=""):
                            self.name = name
                            self.classname = classname
                            self.duration = duration
                            self.status = status
                            self.message = message

                    class XmlReportingTestResult(unittest.TestResult):
                        def __init__(self):
                            super().__init__()
                            self.cases = []

                        def startTest(self, test):
                            super().startTest(test)
                            self._start_time = time.time()
                            bridge.log("PLUGIN", f"Running: {test._testMethodName}", "INFO")

                        def addSuccess(self, test):
                            super().addSuccess(test)
                            duration = time.time() - self._start_time
                            self.cases.append(CaseInfo(test._testMethodName, test.__class__.__name__, f"{duration:.3f}", "Pass"))
                            bridge.log("PLUGIN", f"PASSED: {test._testMethodName} ({duration:.3f}s)", "PASS")

                        def addFailure(self, test, err):
                            super().addFailure(test, err)
                            duration = time.time() - self._start_time
                            msg = self._exc_info_to_string(err, test)
                            self.cases.append(CaseInfo(test._testMethodName, test.__class__.__name__, f"{duration:.3f}", "Fail", msg))
                            bridge.log("PLUGIN", f"FAILED: {test._testMethodName}\n{msg}", "ERROR")

                        def addError(self, test, err):
                            super().addError(test, err)
                            duration = time.time() - self._start_time
                            msg = self._exc_info_to_string(err, test)
                            self.cases.append(CaseInfo(test._testMethodName, test.__class__.__name__, f"{duration:.3f}", "Error", msg))
                            bridge.log("PLUGIN", f"ERROR: {test._testMethodName}\n{msg}", "ERROR")

                    result = XmlReportingTestResult()
                    start_time = time.time()
                    suite.run(result)
                    total_duration = time.time() - start_time

                    test_execution_summary = {
                        "tests_run": result.testsRun,
                        "failures": len(result.failures),
                        "errors": len(result.errors),
                        "time": f"{total_duration:.3f}",
                        "cases": [
                            {
                                "name": c.name,
                                "classname": c.classname,
                                "time": c.duration,
                                "status": c.status,
                                "message": c.message
                            } for c in result.cases
                        ]
                    }
                    bridge.submitReport(json.dumps(test_execution_summary))
                """.trimIndent()

                val execResult = PythonRunner.runCode(
                    code = runnerWrapperScript,
                    bindings = mapOf("bridge" to bridge),
                    onStdoutLine = { line ->
                        synchronized(systemOutBuffer) { systemOutBuffer.add(line) }
                    },
                    onStderrLine = { line ->
                        synchronized(systemErrBuffer) { systemErrBuffer.add(line) }
                    }
                )

                if (!execResult.success) {
                    log("TEST", "Python execution error: ${execResult.stderr}", LogLevel.ERROR)
                    if (isMcp) {
                        mcpTestResults.add(McpTestResult(plugin.shortName, methodName ?: "all", "Error", execResult.stderr, execResult.error?.stackTraceToString()))
                    }
                }

                // 2. Parse summary JSON safely
                val summary = try {
                    if (receivedSummaryJson != null) {
                        Gson().fromJson(receivedSummaryJson, PyExecutionSummary::class.java)
                    } else null
                } catch (e: Exception) {
                    null
                }

                val testsRun = summary?.tests_run ?: if (execResult.success) 1 else 0
                val failuresCount = summary?.failures ?: if (!execResult.success) 1 else 0
                val errorsCount = summary?.errors ?: 0
                val totalTimeStr = summary?.time ?: "0.000"

                // 3. Build DOM XML report matching AntXmlRunListener structure
                val dbf = DocumentBuilderFactory.newInstance()
                val doc = dbf.newDocumentBuilder().newDocument()

                val root = doc.createElement("testsuite")
                doc.appendChild(root)

                root.setAttribute("name", plugin.className.ifBlank { plugin.shortName })
                root.setAttribute("hostname", hostname)
                root.setAttribute("timestamp", isoTimestamp)
                root.setAttribute("tests", testsRun.toString())
                root.setAttribute("failures", failuresCount.toString())
                root.setAttribute("errors", errorsCount.toString())
                root.setAttribute("time", totalTimeStr)

                // Output properties
                val propsElement = doc.createElement("properties")
                root.appendChild(propsElement)

                val propertiesMap = linkedMapOf(
                    "SFR.shortname" to plugin.shortName,
                    "SFR.name" to plugin.title,
                    "SFR.description" to plugin.description,
                    "SFR.category" to plugin.category,
                    "device" to deviceModel,
                    "osversion" to osVersion,
                    "system" to buildDisplayId,
                    "signature" to deviceSerial,
                    "summary" to ""
                )

                propertiesMap.forEach { (k, v) ->
                    val prop = doc.createElement("property")
                    prop.setAttribute("name", k)
                    prop.setAttribute("value", v)
                    propsElement.appendChild(prop)
                }

                // Output testcases
                if (summary != null && summary.cases.isNotEmpty()) {
                    for (caseItem in summary.cases) {
                        val tcName = caseItem.name.ifBlank { "unknown" }
                        val tcClass = caseItem.classname.ifBlank { plugin.shortName }
                        val tcTime = caseItem.time
                        val tcStatus = caseItem.status
                        val tcMessage = caseItem.message

                        val tcElem = doc.createElement("testcase")
                        tcElem.setAttribute("name", tcName)
                        tcElem.setAttribute("classname", "${plugin.shortName}.$tcClass")
                        tcElem.setAttribute("time", tcTime)

                        if (tcStatus == "Fail") {
                            val fElem = doc.createElement("failure")
                            fElem.setAttribute("message", "Assertion Failed")
                            fElem.setAttribute("type", "$tcName($tcClass)")
                            fElem.appendChild(doc.createTextNode(tcMessage))
                            tcElem.appendChild(fElem)

                            if (isMcp) {
                                mcpTestResults.add(McpTestResult(plugin.shortName, tcName, "Fail", "Assertion Failed", tcMessage))
                            }
                        } else if (tcStatus == "Error") {
                            val eElem = doc.createElement("error")
                            eElem.setAttribute("message", "Execution Error")
                            eElem.setAttribute("type", "$tcName($tcClass)")
                            eElem.appendChild(doc.createTextNode(tcMessage))
                            tcElem.appendChild(eElem)

                            if (isMcp) {
                                mcpTestResults.add(McpTestResult(plugin.shortName, tcName, "Error", "Execution Error", tcMessage))
                            }
                        } else {
                            if (isMcp) {
                                mcpTestResults.add(McpTestResult(plugin.shortName, tcName, "Pass"))
                            }
                        }

                        root.appendChild(tcElem)
                    }
                } else if (!execResult.success) {
                    val tcElem = doc.createElement("testcase")
                    tcElem.setAttribute("name", methodName ?: plugin.shortName)
                    tcElem.setAttribute("classname", plugin.shortName)
                    tcElem.setAttribute("time", "0.000")

                    val errElem = doc.createElement("error")
                    errElem.setAttribute("message", "Python Script Error")
                    errElem.setAttribute("type", "ScriptExecutionError")
                    errElem.appendChild(doc.createTextNode(execResult.stderr))
                    tcElem.appendChild(errElem)
                    root.appendChild(tcElem)
                }

                // Output system-out and system-err sections
                val systemOutHeader = StringBuilder()
                systemOutHeader.appendLine()
                systemOutHeader.appendLine("==========================================")
                systemOutHeader.appendLine("[Test Start] : ${plugin.name} on $isoTimestamp")
                systemOutHeader.appendLine("[INFO] ==================================================")
                systemOutHeader.appendLine("[INFO] Starting Python Test: ${plugin.title}")
                systemOutHeader.appendLine("[INFO] Category: ${plugin.category}")
                if (deviceSerial.isNotBlank()) {
                    systemOutHeader.appendLine("[INFO] Target Device: $deviceSerial ($deviceModel, Android $osVersion)")
                }
                systemOutHeader.appendLine("[INFO] ==================================================")

                val formattedSystemOut = synchronized(systemOutBuffer) {
                    systemOutHeader.toString() + systemOutBuffer.joinToString("\n") + "\n==========================================\n"
                }
                val formattedSystemErr = synchronized(systemErrBuffer) {
                    systemErrBuffer.joinToString("\n")
                }

                val outElem = doc.createElement("system-out")
                outElem.appendChild(doc.createCDATASection(formattedSystemOut))
                root.appendChild(outElem)

                val errElem = doc.createElement("system-err")
                errElem.appendChild(doc.createCDATASection(formattedSystemErr))
                root.appendChild(errElem)

                // Write XML using DOMElementWriter
                FileOutputStream(reportFile).use { fos ->
                    val wri: Writer = BufferedWriter(OutputStreamWriter(fos, "UTF8"))
                    wri.write("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
                    DOMElementWriter().write(root, wri, 0, "  ")
                    wri.flush()
                }

                log("TEST", "XML report saved: ${reportFile.name}", LogLevel.INFO)

                // 4. XML patch merging and HTML generation
                val patchFile = File(resultsFolder, "xml-patches/PATCH-junit-report-${plugin.shortName}-$timestamp.xml")
                var retry = 0
                while (!patchFile.exists() && retry < 10) {
                    delay(100)
                    retry++
                }

                if (patchFile.exists()) {
                    delay(200)
                    XmlMerger.merge(reportFile, patchFile)
                    patchFile.delete()
                }

                // Generate HTML report
                generateHtmlReport(reportFile)

                log("TEST", "FINISH: $testTargetName (Report: ${reportFile.name})", if (failuresCount == 0 && errorsCount == 0 && execResult.success) LogLevel.PASS else LogLevel.ERROR)

            } catch (e: Exception) {
                log("TEST", "Unexpected execution error: ${e.message}", LogLevel.ERROR)
                if (isMcp) {
                    mcpTestResults.add(McpTestResult(plugin.shortName, methodName ?: "all", "Error", e.message, e.stackTraceToString()))
                }
            } finally {
                _isRunning.value = false
                if (lockFile.exists()) {
                    lockFile.delete()
                }
            }
        }
    }

    private fun generateHtmlReport(xmlFile: File) {
        try {
            val resourcesDir = File(baseDir, "resources")
            val xsltFile = File(resourcesDir, "summary.xslt")

            if (!xsltFile.exists()) {
                resourcesDir.mkdirs()
                val xsltInputStream = PythonTestExecutor::class.java.getResourceAsStream("/summary.xslt")
                    ?: PythonTestExecutor::class.java.classLoader.getResourceAsStream("summary.xslt")

                if (xsltInputStream != null) {
                    xsltInputStream.use { input ->
                        xsltFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    return
                }
            }

            val factory = TransformerFactory.newInstance()
            val transformer = factory.newTransformer(StreamSource(xsltFile))
            val htmlFile = File(xmlFile.parentFile, xmlFile.name.replace(".xml", ".html"))

            transformer.transform(
                StreamSource(xmlFile),
                StreamResult(htmlFile)
            )
            log("TEST", "HTML report generated: ${htmlFile.name}", LogLevel.INFO)
        } catch (e: Exception) {
            log("TEST", "Failed to generate HTML report: ${e.message}", LogLevel.WARN)
        }
    }
}
