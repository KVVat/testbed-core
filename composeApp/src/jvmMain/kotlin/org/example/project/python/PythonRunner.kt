package org.example.project.python

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class PythonExecutionResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val error: Throwable? = null,
    val returnedValue: Any? = null
)

class LineStreamingOutputStream(private val onLine: (String) -> Unit) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (b == '\n'.code) {
            val line = buffer.toString(StandardCharsets.UTF_8)
            buffer.reset()
            onLine(line)
        } else if (b != '\r'.code) {
            buffer.write(b)
        }
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until (off + len)) {
            write(b[i].toInt())
        }
    }

    @Synchronized
    override fun flush() {
        if (buffer.size() > 0) {
            val line = buffer.toString(StandardCharsets.UTF_8)
            buffer.reset()
            onLine(line)
        }
    }
}

object PythonRunner {

    fun createContext(
        stdoutStream: OutputStream? = null,
        stderrStream: OutputStream? = null
    ): Context {
        val builder = Context.newBuilder("python")
            .allowAllAccess(true)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { true }
            .option("engine.WarnInterpreterOnly", "false")

        if (stdoutStream != null) {
            builder.out(stdoutStream)
        }
        if (stderrStream != null) {
            builder.err(stderrStream)
        }

        return builder.build()
    }

    fun runCode(
        code: String,
        bindings: Map<String, Any?> = emptyMap(),
        onStdoutLine: ((String) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null
    ): PythonExecutionResult {
        val stdoutCapture = ByteArrayOutputStream()
        val stderrCapture = ByteArrayOutputStream()

        val effectiveOut = if (onStdoutLine != null) {
            object : OutputStream() {
                val lineStream = LineStreamingOutputStream(onStdoutLine)
                override fun write(b: Int) { stdoutCapture.write(b); lineStream.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { stdoutCapture.write(b, off, len); lineStream.write(b, off, len) }
                override fun flush() { stdoutCapture.flush(); lineStream.flush() }
                override fun close() { lineStream.flush(); super.close() }
            }
        } else stdoutCapture

        val effectiveErr = if (onStderrLine != null) {
            object : OutputStream() {
                val lineStream = LineStreamingOutputStream(onStderrLine)
                override fun write(b: Int) { stderrCapture.write(b); lineStream.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { stderrCapture.write(b, off, len); lineStream.write(b, off, len) }
                override fun flush() { stderrCapture.flush(); lineStream.flush() }
                override fun close() { lineStream.flush(); super.close() }
            }
        } else stderrCapture

        return try {
            createContext(effectiveOut, effectiveErr).use { context ->
                val polyglotBindings = context.getBindings("python")
                bindings.forEach { (k, v) ->
                    polyglotBindings.putMember(k, v)
                }

                val evalResult = context.eval("python", code)
                effectiveOut.flush()
                effectiveErr.flush()

                PythonExecutionResult(
                    success = true,
                    stdout = stdoutCapture.toString(StandardCharsets.UTF_8),
                    stderr = stderrCapture.toString(StandardCharsets.UTF_8),
                    returnedValue = evalResult
                )
            }
        } catch (e: Throwable) {
            effectiveOut.flush()
            effectiveErr.flush()
            val errMsg = if (e is PolyglotException) e.message ?: e.toString() else e.toString()
            PythonExecutionResult(
                success = false,
                stdout = stdoutCapture.toString(StandardCharsets.UTF_8),
                stderr = stderrCapture.toString(StandardCharsets.UTF_8).ifBlank { errMsg },
                error = e
            )
        }
    }

    fun runFile(
        file: File,
        bindings: Map<String, Any?> = emptyMap(),
        onStdoutLine: ((String) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null
    ): PythonExecutionResult {
        if (!file.exists()) {
            return PythonExecutionResult(
                success = false,
                stdout = "",
                stderr = "File not found: ${file.absolutePath}",
                error = IllegalArgumentException("File not found: ${file.absolutePath}")
            )
        }
        val code = file.readText(StandardCharsets.UTF_8)
        return runCode(code, bindings, onStdoutLine, onStderrLine)
    }
}
