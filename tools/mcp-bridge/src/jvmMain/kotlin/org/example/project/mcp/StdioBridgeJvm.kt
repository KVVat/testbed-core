package org.example.project.mcp

import java.io.BufferedReader
import java.io.InputStreamReader

private val reader = BufferedReader(InputStreamReader(System.`in`))

actual fun readSystemInLine(): String? {
    return reader.readLine()
}

actual fun printStderr(message: String) {
    System.err.println(message)
}

actual fun flushStdout() {
    System.out.flush()
}


