# Project Knowledge for Agent Execution

## 1. Constraints and Critical Lessons

*   **Do Not Modify or Delete `AdbDeviceRule.kt`:** This file contains test-related helper code reserved for future expansions. When resolving production code compilation or dependency issues, do not lazily delete or majorly alter its contents. Any dependency conflicts should be resolved in the Gradle configurations.
*   **Prevent Compilation Error Propagation:** Making massive changes all at once can cause build errors to expand rapidly, making root-cause analysis extremely difficult. Changes must be partitioned and validated incrementally (checking compiler outputs frequently). If build errors spread beyond control, rollback immediately and re-evaluate your approach.
*   **Understand Multiplatform Dependency Resolution:** In Kotlin Multiplatform projects, it is vital to understand how dependencies resolve between source sets (e.g., `commonMain`, `jvmMain`, `commonTest`). Adding dependencies to the wrong source set will trigger unexpected build failures.
*   **Verify Build Cleanliness Before Handover:** Unless you are executing in an autonomous self-contained loop, always run a compilation check (such as `./gradlew assemble`) after code edits to ensure there are no compilation errors before returning control to the user.
*   **Compose Desktop and `TextOverflow.Ellipsis`:** `androidx.compose.ui.text.font.TextOverflow.Ellipsis` might not be supported depending on the Compose Multiplatform version. Avoid using it in `jvmMain` environments, or implement an alternative text overflow truncation logic.
*   **Preventing ADB Socket Hangs and Thread Freezes (Crucial)**:
    Direct network-based commands through the Adam library (`adb.execute(ShellCommandRequest(...))`) rely on uninterruptible Java Socket I/O. If the device goes into a sleep/wake transition (especially during `press_key` with "POWER" or `get_device_state` query), the socket connection can hang indefinitely, ignoring coroutine timeouts like `withTimeoutOrNull`. To prevent this, critical commands must be wrapped or executed using OS-level process timeouts (`Process.waitFor(timeout, unit)` via a ProcessBuilder helper like `executeShellViaProcessBuilder` in `AdbObserver.kt`) to ensure control is always returned.
*   **Non-Interruptible CLI Command Hangs**:
    Executing commands that can block on network I/O or resolve infinitely (like `ping -c 3 google.com` inside `execute_adb_shell`) without process-level timeouts will leak background threads inside the Coroutine Dispatcher. If multiple blocked commands run, the Dispatcher pool will exhaust, resulting in complete MCP server freezes. Always ensure process-based timeouts are enforced for CLI executions.
    *Note: Killing the host-side `adb` process with `Process.destroy()` will free up host-side thread resources, but the child process spawned inside the Android device shell (such as `ping`) may continue to run on the device as a zombie, consuming battery and CPU. Future architecture should address target-side process group signals.*

## 2. ADB Operations using Adam Library

Important patterns for managing ADB connections via the Adam library:

*   **Retrieving the Adam Client:** Inside `AdbDeviceRule.kt`, construct the client instance using `AndroidDebugBridgeClientFactory().build()`. High-level logic observers (e.g., `AdbObserver`) access the client through the rule's `adb` property.
*   **Executing Shell Commands:** 
    *   General commands are executed using `adamClient.execute(ShellCommandRequest("your command"), serial)`.
    *   For escaping arguments containing spaces (like `input text "hello world"`), replace spaces with `%s`: `text.replace(" ", "%s")`.
*   **Logcat Streaming:**
    *   To stream logcat in real-time, use `ChanneledLogcatRequest`.
    *   Executing `adamClient.execute(request = ChanneledLogcatRequest(), serial = serial)` returns a `ReceiveChannel<String>`.
    *   Consume this channel asynchronously using `consumeEach { line -> ... }` on a dedicated dispatcher.
    *   For detail, refer to: [Adam Logcat Documentation](https://malinskiy.github.io/adam/docs/logcat/logcat/)
*   **Device Rebooting:**
    *   To reboot a device into a specific mode, use `RebootRequest`.
    *   Example: `adamClient.execute(RebootRequest(RebootMode.BOOTLOADER), serial)`. These classes are located under `com.malinskiy.adam.request.misc`.
*   **Coroutines & Thread Safety:**
    *   Always execute heavy ADB network operations within the `Dispatchers.IO` coroutine scope to avoid blocking the main UI thread.
    *   Bind coroutine jobs to the UI lifecycle using `viewModel.viewModelScope.launch { ... }` inside observers.

## 3. Project Structure & Dependency Management

*   **`libs.versions.toml`**: Centralize all dependencies and versions using Gradle Version Catalogs. Always declare new library coordinates and aliases here first before adding them to build files.
*   **`build.gradle.kts`**:
    *   Add cross-platform libraries (Compose Multiplatform core, Adam, JUnit etc.) to `commonMain.dependencies`.
    *   Add JVM-only dependencies (Compose Desktop integrations, `kotlinx-coroutines-swing`) to `jvmMain.dependencies`.
    *   **JUnit Placement:** If a production/plugin class needs direct access to JUnit runner components (like `AdbDeviceRule.kt`), place JUnit dependencies inside `commonMain` rather than standard test-only scopes.

## 4. Logging Best Practices

*   `AppViewModel.log(tag: String, message: String, level: LogLevel = LogLevel.INFO)`: Prefer using this overload since calling layers can omit the default log level.
*   Log filtering and maximum buffer limits must be strictly maintained to prevent performance bottlenecks.

## 5. MCP Server Manual Diagnostics & Verification

If the LLM client cannot directly bind to the TestBed Core MCP server, you can manually verify SSE connectivity on port 11452 by running this script:

```bash
#!/bin/bash
# 1. Connect to SSE Endpoint and retrieve SessionID
rm -f /tmp/sse_out_$$
curl -sN http://localhost:11452/mcp > /tmp/sse_out_$$ &
SSE_PID=$!
sleep 2

# (CRITICAL) Clean carriage returns (CR) from HTTP headers
SESSION_INFO=$(grep "^data: " /tmp/sse_out_$$ | head -n 1 | sed 's/^data: //' | tr -d '\r')

if [ -z "$SESSION_INFO" ]; then
    echo "Failed to connect to MCP server"
    kill $SSE_PID 2>/dev/null
    exit 1
fi

MSG_ENDPOINT="http://localhost:11452${SESSION_INFO}"

# 2. Call tools/list
echo -e "\n--- Calling tools/list ---"
curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'

sleep 1

# 3. Call tool (e.g. get_logcat)
echo -e "\n--- Calling get_logcat ---"
curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"get_logcat","arguments":{"tags":["ActivityManager"], "level":"I", "max_lines":5}}}'

sleep 2

# 4. Check outputs
echo -e "\n--- SSE Output ---"
cat /tmp/sse_out_$$

kill $SSE_PID 2>/dev/null
rm -f /tmp/sse_out_$$
```

## 6. Mutton Agent (Android Test Client) Deployment

Troubleshooting checklist for deploying mutton-agent to physical devices:

*   **Google Play Protect Installs Blocking:** When executing background installs (`adb install`/`pm install`) of raw test APKs, Google Play Protect may prompt a security warning dialog that hangs the execution thread indefinitely. Ensure Play Protect is **disabled** on the target device, or resolve the dialog manually on-screen.
*   **Non-Blocking Agent Execution (`am instrument`):** Starting tests via `am instrument` blocks the execution process until complete. If invoked through Adam, this holds Ktor connection response pools. Wrap it in a non-blocking coroutine `launch(Dispatchers.IO)` and verify agent presence through ping socket handshake instead.
*   **JVM and `org.json` incompatibility:** Do not import `org.json.JSONObject` in shared code running on Compose Desktop. It will trigger `NoClassDefFoundError` due to classloader mismatch. Use `Gson` or `kotlinx.serialization` instead.
*   **Debugging Output:** Avoid using raw `println()` inside Android agent logic. Instead, utilize `android.util.Log.i(TAG, message)` so traces can be properly routed through logcat.
*   **Mutton Agent Processing Asynchronization (Fundamental Fix for UI Dump)**:
    Implementing a two-phase async wrapper on the host side (`AdbObserver.kt`) is only a temporary workaround. If the `mutton-agent` (`AgentTest.kt` on the device) performs UI dumps or key actions synchronously, it still blocks the underlying ADB command execution and Ktor connections, leading to freezes in unconfigured LLM clients. The execution model inside `mutton-agent` must be fundamentally refactored to handle requests asynchronously (e.g., running the dump task in a background coroutine/thread on the device, returning immediately, and allowing the host to poll or receive the result via a separate non-blocking channel).

## 7. Direct MCP Connections

LLM Agents can connect directly to TestBed Core's SSE interface (`http://localhost:11452/mcp`). Avoid using `mcp_call.sh` shell redirects except for local manual command line debugging.

## 8. X.509 Certificate and Revocation (OCSP/CRL) Verification Insights

When dealing with certificate validation and mock responders on modern Android versions (Android 15+ / Conscrypt):

*   **Strict KeyUsage Constraints (Conscrypt):** Modern Android Conscrypt/OkHttp engines strictly require the `keyUsage` extension (e.g., `critical, digitalSignature, keyEncipherment`) and `extendedKeyUsage` (e.g., `serverAuth`) in server certificates. Lack of these constraints will cause immediate `SSLHandshakeException` (connection closed / 525) during TLS handshakes. Always generate certificates using a compliant extensions template file.
*   **OpenSSL CA Index Duplicate DN Error:** By default, OpenSSL CA index databases (`index.txt`) enforce unique subject DN constraints. Having multiple mock certificates with the same DN (e.g. `/CN=localhost` for different ports) will crash the responder with `Error creating name index` on startup. To prevent this, always create a matching `index.txt.attr` file containing `unique_subject = no` in the DB directory.
*   **AIA Port Alignments:** Ensure the Authority Info Access (AIA) OCSP URI defined inside the certificate extension file matches the exact port number where the host OCSP responder is running.
*   **Testbed Port Reversal Automation:** Avoid requesting developers to run manual `adb reverse` commands before testing. Instead, implement setup/teardown automation loops inside the test class using `ProcessBuilder("adb", "-s", serial, "reverse", "tcp:port", "tcp:port")` to dynamically route all necessary ports (e.g., mock endpoints 4443-4448 and responders 8888-8891) and remove them on completion.
