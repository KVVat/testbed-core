package org.example.project.adb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AdbRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val adbObserver = AdbObserver(scope)
    
    val adbState: StateFlow<AdbState> = adbObserver.adbState
    val logs: SharedFlow<LogEvent> = adbObserver.logs
    val logcatLines: SharedFlow<String> = adbObserver.logcatLines
    val logcatFlush: SharedFlow<Unit> = adbObserver.logcatFlush
    val screenshotStream: SharedFlow<String> = adbObserver.screenshotStream

    init {
        scope.launch {
            adbObserver.observeAdb()
        }
    }

    fun setRunning(isRunning: Boolean) {
        adbObserver.isRunning = isRunning
    }

    suspend fun startLogcat(pastMinutes: Int = 10) = adbObserver.startLogcat(pastMinutes)
    suspend fun stopLogcat() = adbObserver.stopLogcat()
    suspend fun captureScreenshot() = adbObserver.captureScreenshot()
    suspend fun sendText(text: String) = adbObserver.sendText(text)
    suspend fun clearAppData(packageName: String) = adbObserver.clearAppData(packageName)
    suspend fun rebootToBootloader() = adbObserver.rebootToBootloader()
    suspend fun sendKeyEvent(keyCode: Int) = adbObserver.sendKeyEvent(keyCode)
    suspend fun setupMuttonAgent(forceInstall: Boolean = false) = adbObserver.setupMuttonAgent(forceInstall)
    suspend fun pingMuttonAgent() = adbObserver.pingMuttonAgent()
    suspend fun dumpMuttonAgent(includeImage: Boolean = false, quality: Int = 2, silent: Boolean = false) = adbObserver.dumpMuttonAgent(includeImage, quality, silent)
    suspend fun executeAdbShell(command: String) = adbObserver.executeAdbShell(command)
    suspend fun shellExecute(command: String) = adbObserver.shellExecute(command)
    suspend fun shellReceive(taskId: String) = adbObserver.shellReceive(taskId)

    suspend fun tapCoordinate(x: Int, y: Int) = adbObserver.tapCoordinate(x, y)
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int) = adbObserver.swipe(startX, startY, endX, endY)
    suspend fun pressKey(keycode: String) = adbObserver.pressKey(keycode)
    suspend fun inputText(text: String, pressEnter: Boolean = true) = adbObserver.inputText(text, pressEnter)
    suspend fun clearLogcatBuffer() = adbObserver.clearLogcatBuffer()
    suspend fun getFilteredLogcat(tags: List<String>, level: String, grepPattern: String, maxLines: Int, process: String = "") = adbObserver.getFilteredLogcat(tags, level, grepPattern, maxLines, process)
    suspend fun getDeviceInfo() = adbObserver.getDeviceInfo()
    suspend fun getDeviceState() = adbObserver.getDeviceState()
    suspend fun openSettings(panel: String, packageName: String? = null) = adbObserver.openSettings(panel, packageName)
    suspend fun pushFile(hostPath: String, devicePath: String, useRoot: Boolean = false) = adbObserver.pushFile(hostPath, devicePath, useRoot)
    suspend fun pullFile(devicePath: String, hostPath: String, useRoot: Boolean = false) = adbObserver.pullFile(devicePath, hostPath, useRoot)
    suspend fun listDirectory(path: String, useRoot: Boolean = false) = adbObserver.listDirectory(path, useRoot)
    suspend fun getFilePreview(path: String, useRoot: Boolean = false) = adbObserver.getFilePreview(path, useRoot)
    suspend fun deleteFile(path: String, useRoot: Boolean = false) = adbObserver.deleteFile(path, useRoot)
    suspend fun installApp(apkPath: String, reinstall: Boolean = true) = adbObserver.installApp(apkPath, reinstall)
    suspend fun uninstallApp(packageName: String, keepData: Boolean = false) = adbObserver.uninstallApp(packageName, keepData)
}
