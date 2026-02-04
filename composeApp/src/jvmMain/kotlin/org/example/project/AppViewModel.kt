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

class AppViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState = _uiState.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogLine>(replay = 100)
    val logFlow = _logFlow.asSharedFlow()

    // ADB監視クラス (ViewModelが保持し、initで起動)
    private val adbObserver = AdbObserver(this)

    init {
        // ViewModel起動と同時にADB監視を開始
        startAdbObservation()
    }

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
}