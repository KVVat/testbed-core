# TestBed Core Future Development Plans

This document tracks planned features, refactoring tasks, and UI/UX improvements for both the **Host Application (Desktop GUI)** and the **Mutton Agent (Android Client)**.

---

## 1. Host Application (Desktop GUI) Improvements

### A. Screen Snapshots (Thumbnails) in History Panel
* **Background**: The current UI Inspector's History list displays layout entries using only timestamps and UUID texts. It is difficult for human developers to quickly recognize which layout corresponds to which screen state at a glance.
* **Proposed Implementation**:
  * Display a small scaled-down screenshot thumbnail (image icon) on the left side of each history item card.
  * Load images asynchronously from the local disk path (`pngFilepath` from the SQLite database) to prevent UI frame drops and scrolling lags.
  * If a history record does not contain a screenshot (e.g., failed dump or layout-only check), show a fallback vector placeholder icon.

---

## 1.5. TestBed CLI Submodule (Python-less Stdio-to-SSE Bridge) - [COMPLETED]

### Background
LLM clients (like Antigravity and Cline) communicate with MCP servers using a Stdio stream. Since TestBed Core runs a background Server-Sent Events (SSE) server over Ktor, we currently bridge the Stdio-to-SSE channel using a Python script (`mcp_stdio_bridge.py`). This forces end-users to install Python 3 on their host machines, breaking the project's "zero-dependency, portable deployment" philosophy.

### Proposed Solution: Lightweight Kotlin CLI Submodule
We should implement a dedicated Gradle submodule (e.g., `:testbed-cli`) that compiles into a lightweight native binary or JVM executable to replace the Python bridge.

* **Design Concept**:
  * The module will **NOT** depend on Compose or any UI frameworks, keeping its binary size minimal and startup times near-instant.
  * It will read JSON-RPC from standard input (`System.in`), proxy requests using Ktor client HTTP POST to the background server, and direct incoming SSE events back to standard output (`System.out`).
* **Implementation Options**:
  * **Option A: Kotlin/Native (Recommended)**: Compile the CLI utility into native binaries (`testbed-cli.exe` for Windows, `testbed-cli` ELF for Linux/macOS) using Kotlin/Native. This achieves absolute zero-dependency deployment with zero startup overhead.
  * **Option B: Lightweight JVM Uber-JAR**: Pack a small compiled CLI `.jar` and execute it using the bundled JRE that is already packaged inside the TestBed Core native distribution launcher.
* **Distribution**:
  * Embed the compiled CLI binary directly inside the packaged `.zip` distribution. LLM configurations will simply point directly to the local binary path, eliminating the need for `python3`.

---

## 1.6. Local ADB Kill-Switch for Remote/VM Environments

### Background
When working in remote development setups (e.g., connecting to target devices inside a VM or forwarding ADB sockets via SSH to a remote server), having the local machine's background ADB daemon running can interfere with connection routing, causing port conflicts and connection errors. Currently, developers have to manually type `adb kill-server` in the terminal repeatedly to prevent the local server from auto-starting.

### Proposed Solution
Introduce a global **"ADB Kill-Switch"** toggle inside the Host Application GUI (and optionally as an MCP tool).

* **Proposed Behavior**:
  * When toggled **ON**:
    1. The host application immediately executes `adb kill-server` locally.
    2. The application temporarily suspends its background ADB polling loops (`observeAdb()`) and halts any automated SDK client socket queries that could implicitly trigger `adb start-server`.
    3. The application intercepts and blocks manual trigger operations (like screenshot refreshing or manual app installations) to ensure the local daemon stays dead.
  * When toggled **OFF**:
    1. The polling loop resumes, allowing the local ADB daemon to start and connect to devices normally.

---

## 2. Mutton Agent (Android Client) Improvements

### A. Socket Connection Multi-Threading (Concurrent Request Handling)
* **Background**: Mutton Agent's socket handler processes incoming requests synchronously within a single-threaded loop, which causes concurrent socket requests from the host (such as long-running checks and parallel inspect commands) to hang.
* **Proposed Implementation**:
  * Refactor the socket server loop to spawn a dedicated coroutine (`launch(Dispatchers.IO)`) or worker thread for each accepted connection.
  * Introduce a `Mutex` lock to serialize access to the `UiDevice` / `UiAutomation` APIs, keeping device interactions thread-safe while allowing parallel socket network I/O.

### B. Streaming Command Deprecation and Code Cleanup
* **Background**: Continuous streaming commands (`start_stream`, `start_dump_stream`) are resource-heavy and have been superseded by the checkpoint-based DB archiver.
* **Proposed Implementation**:
  * Remove unused stream handlers, background thread loops, and related variables from `AgentTest.kt` to clean up the code.

## 3. Future Security & Diagnostics MCP Tools (The "dbg" Suite)

### A. App Process Memory Zeroization Auditor (`audit_process_memory`)
* **Background**: Proving that sensitive data (such as plain-text passwords, cryptographic keys, or session tokens) is completely wiped from RAM after use (zeroization) is a critical requirement for Common Criteria (MDFPP) certification (specifically `FCS_CKM_EXT.4`). Since modern Android Keystore utilizes native-backed cryptographic libraries (e.g., Conscrypt, BoringSSL), sensitive keys can easily leak into the **Native Heap** (C/C++ memory space), making them completely invisible to standard Java heap dumps.
* **Proposed Implementation**:
  * Since the testbed runs in a `root` (userdebug) environment, we can temporarily set SELinux to permissive mode via `setenforce 0` to bypass system-level sandbox restrictions.
  * Extract the target service or application's PID (e.g., KeyMint HAL service `android.hardware.security.keymint-service` or the target app).
  * Read the virtual memory layout map from `/proc/<pid>/maps` to identify writable memory regions (heaps, stacks, anonymous memory).
  * Open `/proc/<pid>/mem`, seek to the mapped addresses, and scan the raw bytes for specified key patterns or plain-text credentials.
  * Report any matches as a test assertion failure.

### B. JVM Heap Dump & Analysis (`dump_app_heap`)
* **Background**: Inspecting Java Heap objects without launching heavy Android Studio Profilers.
* **Proposed Implementation**:
  * Trigger an automated heap dump via `am dumpheap <pid> <path>` on the device.
  * Pull the raw `.hprof` file to the host and convert it using `hprof-conv`.
  * Parse the converted heap dump on the host JVM to report instances of specific classes (e.g., `SecretKeySpec`) and detect potential leaks.

### C. Process Thread Stack Dump (`dump_process_threads`)
* **Background**: Fast diagnosing of deadlocks or ANR freezes on the target device.
* **Proposed Implementation**:
  * Trigger a thread stack trace dump by sending a `kill -3 <pid>` signal to the target process.
  * Retrieve and parse the generated `/data/anr/traces.txt` on the host to highlight blocked or deadlocked threads.