package org.example.project.tools

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

object SingleInstanceLock {
    private var lockFile: File? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * Attempts to acquire an exclusive lock for the application instance.
     * Returns true if lock was successfully acquired, false if another instance is already running.
     */
    fun acquire(lockFileName: String = "testbed-core.lock"): Boolean {
        return try {
            val tmpDir = File(System.getProperty("java.io.tmpdir"))
            val targetLockFile = File(tmpDir, lockFileName)
            lockFile = targetLockFile

            val raf = RandomAccessFile(targetLockFile, "rw")
            randomAccessFile = raf
            val fc = raf.channel
            channel = fc

            val fileLock = fc.tryLock()
            if (fileLock != null && fileLock.isValid) {
                lock = fileLock
                try {
                    raf.setLength(0)
                    val pid = ProcessHandle.current().pid()
                    raf.writeBytes("PID: $pid\nStartTime: ${java.time.Instant.now()}\n")
                } catch (_: Throwable) {
                    // Diagnostic info write failure is non-fatal
                }

                // Ensure lock is released on normal JVM exit or termination
                Runtime.getRuntime().addShutdownHook(Thread {
                    release()
                })
                true
            } else {
                cleanup()
                false
            }
        } catch (e: Exception) {
            println("[SingleInstanceLock] Failed to acquire lock (another instance likely running): ${e.message}")
            cleanup()
            false
        }
    }

    /**
     * Releases the acquired lock, closes open channels, and removes the lock file.
     */
    fun release() {
        try {
            if (lock?.isValid == true) {
                lock?.release()
            }
        } catch (_: Throwable) {}
        lock = null

        cleanup()

        try {
            lockFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        } catch (_: Throwable) {}
        lockFile = null
    }

    private fun cleanup() {
        try {
            channel?.close()
        } catch (_: Throwable) {}
        channel = null

        try {
            randomAccessFile?.close()
        } catch (_: Throwable) {}
        randomAccessFile = null
    }
}
