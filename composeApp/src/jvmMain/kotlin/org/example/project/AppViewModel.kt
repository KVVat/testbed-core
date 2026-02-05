package org.example.project

import java.time.LocalTime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.adb.AdbObserver
import org.example.project.adb.FastbootClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File

class AppViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState = _uiState.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogLine>(replay = 100)
    val logFlow = _logFlow.asSharedFlow()

    // ADB監視クラス (ViewModelが保持し、initで起動)
    private val adbObserver = AdbObserver(this)
    private val fastbootClient = FastbootClient()

    init {
        // ViewModel起動と同時にADB監視を開始
        startAdbObservation()
    }

    fun pressHome() = viewModelScope.launch { adbObserver.sendKeyEvent(3) }  // KEYCODE_HOME
    fun pressBack() = viewModelScope.launch { adbObserver.sendKeyEvent(4) }  // KEYCODE_BACK
    fun pressEnter() = viewModelScope.launch { adbObserver.sendKeyEvent(66) } // KEYCODE_ENTER

    private fun startAdbObservation() {
        viewModelScope.launch {
            log("SYSTEM", "Starting ADB Observer...", LogLevel.INFO)
            try {
                adbObserver.observeAdb()
            } catch (e: Exception) {
                log("ADB", "Observer error: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    // --- Public Actions called from UI or Observer ---

    fun toggleAdbIsValid(isValid: Boolean) {
        _uiState.update { it.copy(adbIsValid = isValid) }
        val status = if (isValid) "Connected" else "Disconnected"
        log("ADB", "Status changed: $status", if (isValid) LogLevel.PASS else LogLevel.ERROR)
    }

    fun setRunning(isRunning: Boolean) {
        _uiState.update { it.copy(isRunning = isRunning) }
    }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = LocalTime.now().toString().take(8)
        viewModelScope.launch {
            _logFlow.emit(LogLine(timestamp, tag, message, level))
        }
    }

    fun captureScreenshot() {
        viewModelScope.launch {
            adbObserver.captureScreenshot()
        }
    }

    /**
     * デバイスにテキストを送信します。
     * @param text 送信する文字列
     */
    fun sendText(text: String) {
        log("UI", "Requesting to send text...")
        viewModelScope.launch {
            adbObserver.sendText(text)
        }
    }

    /**
     * アプリのデータを消去します。
     */
    fun clearAppData() {
        log("UI", "Requesting to clear app data...")
        viewModelScope.launch {
            adbObserver.clearAppData("org.example.project")
        }
    }

    /**
     * Bootイメージの書き込みを一括で行います。
     * @param imageFile 書き込むbootイメージファイル
     */
    fun batchFlashBootImage(imageFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            log("FLASH", "Starting batch flash process for ${imageFile.name}", LogLevel.INFO)

            // 1. adb reboot bootloader
            log("FLASH", "[1/5] Rebooting to bootloader...", LogLevel.INFO)
            adbObserver.rebootToBootloader()
            if (!uiState.value.adbIsValid) {
                log("FLASH", "ADB device not found. Aborting.", LogLevel.ERROR)
                return@launch
            }

            // 2. 10秒待機
            log("FLASH", "[2/5] Waiting for device to enter bootloader mode (10s)...", LogLevel.INFO)
            delay(10000)

            // 3. fastboot devices
            log("FLASH", "[3/5] Detecting device in fastboot mode...", LogLevel.INFO)
            val devices = fastbootClient.getDevices()
            if (devices.isEmpty()) {
                log("FLASH", "No devices found in fastboot mode. Aborting.", LogLevel.ERROR)
                return@launch
            }
            val serial = devices.first()
            log("FLASH", "Device found: $serial", LogLevel.PASS)

            // 4. fastboot flash boot <image>
            log("FLASH", "[4/5] Flashing boot partition...", LogLevel.INFO)
            val flashResult = fastbootClient.flashPartition(serial, "boot", imageFile)
            if (flashResult.exitCode != 0) {
                log("FLASH", "Failed to flash boot partition: ${flashResult.output}", LogLevel.ERROR)
                return@launch
            }
            log("FLASH", "Flash successful: ${flashResult.output}", LogLevel.PASS)

            // 5. fastboot reboot
            log("FLASH", "[5/5] Rebooting device to system...", LogLevel.INFO)
            val rebootResult = fastbootClient.reboot(serial)
            if (rebootResult.exitCode != 0) {
                log("FLASH", "Failed to reboot device: ${rebootResult.output}", LogLevel.ERROR)
                return@launch
            }
            log("FLASH", "Batch flash process completed successfully.", LogLevel.PASS)
        }
    }
}