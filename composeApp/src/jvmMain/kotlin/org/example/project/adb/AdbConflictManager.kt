package org.example.project.adb

import org.example.project.LogLevel

data class ConflictingAdbProcess(
    val pid: Long,
    val command: String,
    val isNonStandardPort: Boolean = false,
    val port: Int? = null
)

object AdbConflictManager {

    /**
     * Finds active adb processes across the OS.
     * Checks both standard JVM ProcessHandle and OS-level ps for complete command-line arguments.
     */
    fun findConflictingProcesses(): List<ConflictingAdbProcess> {
        val currentPid = ProcessHandle.current().pid()
        val resultMap = mutableMapOf<Long, ConflictingAdbProcess>()

        // 1. Try OS-level ps command on Unix/macOS for full command line arguments
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("mac") || osName.contains("linux")) {
            try {
                val process = ProcessBuilder("ps", "-axo", "pid,command").start()
                val reader = process.inputStream.bufferedReader()
                reader.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("PID")) continue
                        val parts = trimmed.split(Regex("\\s+"), limit = 2)
                        if (parts.size >= 2) {
                            val pid = parts[0].toLongOrNull() ?: continue
                            val cmd = parts[1]
                            if (pid == currentPid) continue

                            // Exclude inspection tools and wrappers
                            if (cmd.contains("grep") || cmd.contains("ps -axo") || cmd.contains("findConflictingProcesses")) continue

                            if (isAdbCommandLine(cmd)) {
                                val (isNonStd, port) = extractPortInfo(cmd)
                                resultMap[pid] = ConflictingAdbProcess(
                                    pid = pid,
                                    command = cmd,
                                    isNonStandardPort = isNonStd,
                                    port = port
                                )
                            }
                        }
                    }
                }
                process.waitFor()
            } catch (ignored: Exception) {}
        }

        // 2. Cross-platform check via ProcessHandle
        try {
            ProcessHandle.allProcesses().forEach { handle ->
                val pid = handle.pid()
                if (pid == currentPid) return@forEach

                val info = handle.info()
                val cmd = info.command().orElse("")
                val cmdLine = info.commandLine().orElse("")
                val fullCmd = if (cmdLine.isNotBlank()) cmdLine else cmd

                if (isAdbCommandLine(fullCmd) || isAdbCommandLine(cmd)) {
                    if (!resultMap.containsKey(pid)) {
                        val (isNonStd, port) = extractPortInfo(fullCmd)
                        resultMap[pid] = ConflictingAdbProcess(
                            pid = pid,
                            command = fullCmd.ifBlank { "adb (PID: $pid)" },
                            isNonStandardPort = isNonStd,
                            port = port
                        )
                    }
                }
            }
        } catch (ignored: Exception) {}

        return resultMap.values.toList()
    }

    private fun isAdbCommandLine(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return lower.endsWith("/adb") ||
                lower.endsWith("\\adb.exe") ||
                lower == "adb" ||
                lower.contains("/adb ") ||
                lower.contains("\\adb.exe ") ||
                lower.startsWith("adb ") ||
                lower.contains("fork-server") ||
                (lower.contains("adb") && lower.contains("server"))
    }

    private fun extractPortInfo(cmd: String): Pair<Boolean, Int?> {
        val portRegex = Regex("""(?:-L\s+tcp:|-P\s+|tcp:)(\d{4,5})""")
        val match = portRegex.find(cmd)
        val port = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val isNonStandard = (port != null && port != 5037)
        return Pair(isNonStandard, port)
    }

    /**
     * Determines whether the detected processes constitute a conflict requiring cleanup.
     * True ONLY if:
     * 1. A non-standard port ADB process is running (e.g. port 5038, 5039).
     * 2. Multiple ADB server instances are running simultaneously.
     */
    fun hasConflict(isAdbConnected: Boolean, conflicts: List<ConflictingAdbProcess>): Boolean {
        if (conflicts.isEmpty()) return false

        // Any non-standard port process (e.g. port 5038) is an immediate conflict
        if (conflicts.any { it.isNonStandardPort }) return true

        // Multiple adb server instances running simultaneously
        val serverInstances = conflicts.filter { 
            it.command.contains("fork-server") || it.command.contains("server") || it.command.contains("adb ") 
        }
        if (serverInstances.size > 1) return true

        // If all detected processes belong to a single standard 5037 server, it is NOT a conflict.
        return false
    }

    /**
     * Forcibly terminates all conflicting ADB processes and cleanly restarts the standard ADB server on port 5037.
     */
    fun cleanupAndRestartAdb(adbPath: String = "adb", logCallback: ((String, LogLevel) -> Unit)? = null): Boolean {
        return try {
            logCallback?.invoke("Cleaning up conflicting ADB processes...", LogLevel.INFO)
            val conflicts = findConflictingProcesses()
            for (p in conflicts) {
                logCallback?.invoke("Terminating process PID ${p.pid}: ${p.command.take(60)}...", LogLevel.INFO)
                ProcessHandle.of(p.pid).ifPresent { handle ->
                    try {
                        handle.destroyForcibly()
                    } catch (ignored: Exception) {}
                }
            }

            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("mac") || osName.contains("linux")) {
                try {
                    Runtime.getRuntime().exec(arrayOf("pkill", "-9", "adb")).waitFor()
                } catch (ignored: Exception) {}
            }

            try {
                Runtime.getRuntime().exec(arrayOf(adbPath, "kill-server")).waitFor()
            } catch (ignored: Exception) {}

            Thread.sleep(600)

            logCallback?.invoke("Starting fresh ADB server on standard port 5037...", LogLevel.INFO)
            val startProcess = Runtime.getRuntime().exec(arrayOf(adbPath, "start-server"))
            val exitCode = startProcess.waitFor()
            
            if (exitCode == 0) {
                logCallback?.invoke("ADB server restarted cleanly on port 5037.", LogLevel.PASS)
                true
            } else {
                logCallback?.invoke("ADB server start exited with code $exitCode", LogLevel.WARN)
                false
            }
        } catch (e: Exception) {
            logCallback?.invoke("Failed to clean up ADB: ${e.message}", LogLevel.ERROR)
            false
        }
    }
}
