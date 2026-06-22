@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.example.project.mcp

import platform.posix.*

actual fun readSystemInLine(): String? {
    return readLine()
}

actual fun printStderr(message: String) {
    fprintf(stderr, "%s\n", message)
    fflush(stderr)
}

actual fun flushStdout() {
    fflush(stdout)
}
