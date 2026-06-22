# TestBed Core: Model Context Protocol (MCP) Specification

This specification defines the complete set of MCP tools exposed by TestBed Core to enable LLM agents (such as Antigravity) to perform sensing, execution, adb system diagnostics, and JUnit test automation on target Android devices.

---

## 1. Sensing Tools (Environment Observability)

### `get_ui_dump`
* **Description:** Retrieves the current Android on-screen UI hierarchy. If execution takes more than 1 second, it returns immediately with `status: "running"` and a `task_id`. Use `receive_ui_dump` to check status and retrieve the output.
* **Parameters:**
  * `format` (string, optional, default: `"summary"`): `"summary"` (compact list) or `"json"` (full tree).
  * `include_image` (boolean, optional, default: `false`): If true, captures a screenshot.
  * `image_quality` (int, optional, default: `4`): Image quality (1-4).
  * `tag` (string, optional): Custom key string to tag the saved layout in history.
* **Returns:** UI dump output OR a task token:
  * Fast execution: Plain text of the dump (or JSON structure if format is json).
  * Long execution: `{"status": "running", "task_id": "UUID-string"}`.

### `get_screen`
* **Description:** Captures a screenshot of the connected device. Resizes the image to fit within a 1024x1024 bounding box (maintaining aspect ratio) and compresses it as a JPEG. If a tag is provided, the layout and screenshot are saved to the history DB.
* **Parameters:**
  * `tag` (string, optional): Optional custom string key to tag the saved screen layout in history registry.
* **Returns:** Purely an embedded base64 JPEG image (`ImageContent` format) representing the screenshot. Useful for multimodal LLM context ingestion without text layout overhead.


### `get_ui_dump_history`
* **Description:** Retrieves a historical UI layout dump by UUID, Tag, or latest relative Index.
* **Parameters:**
  * `uuid` (string, optional): Query layout directly by its unique UUID.
  * `tag` (string, optional): Retrieve the latest layout matching this tag.
  * `index` (int, optional, default: `0`): Relative index offset from latest.
  * `format` (string, optional, default: `"summary"`): Output format (`"summary"` or `"json"`).
* **Returns:** The historical UI hierarchy text. The metadata footer includes `png_path` containing the absolute local file path of the captured screenshot, enabling direct image access without Base64 overhead.

### `receive_ui_dump`
* **Description:** Polls and retrieves the UI dump output of a pending background task initiated by `get_ui_dump`.
* **Parameters:**
  * `task_id` (string, required): The task ID returned by `get_ui_dump`.
* **Returns:** UI dump output if completed, OR a JSON containing `status: "running"` if still active.

### `get_device_state`
* **Description:** Retrieves system-level state properties from the device.
* **Parameters:** None.
* **Returns:** JSON properties such as `is_screen_on`, `is_locked`, `foreground_package` (currently active package).

### `get_device_info`
* **Description:** Extracts device hardware and OS characteristics (wrapper around `getprop`). Helps agents adapt to UI scaling or Android version differences.
* **Parameters:** None.
* **Returns:** JSON containing `model`, `os_version` (API Level / OS major), `screen_size` (width/height), `abi`, etc.

---

## 2. Action Tools (User Interface Interactions)

Action tools trigger physical user interface operations on the device. They execute and block until completion, returning a simple confirmation JSON. They no longer implicitly return the UI dump; to see screen updates, call `get_ui_dump` separately.

### `tap`
* **Description:** Taps the screen at specified physical coordinates `(x, y)`.
* **Parameters:**
  * `x` (int, required): X coordinate.
  * `y` (int, required): Y coordinate.
* **Returns:** Confirmation JSON (e.g., `{"status":"ok","action":"tap",...}`).

### `input_text`
* **Description:** Enters text into the currently focused input field.
* **Parameters:**
  * `text` (string, required): The string to type. Spaces are automatically escaped.
  * `press_enter` (boolean, optional, default: `true`): If true, fires a virtual Enter key press after entering text.
* **Returns:** Confirmation JSON.

### `swipe`
* **Description:** Drags across the screen from start coordinates to end coordinates.
* **Parameters:**
  * `start_x` (int, required)
  * `start_y` (int, required)
  * `end_x` (int, required)
  * `end_y` (int, required)
* **Returns:** Confirmation JSON.

### `press_key`
* **Description:** Fires a physical or virtual system key event.
* **Parameters:**
  * `keycode` (string, required): Keycode alias (e.g., `"BACK"`, `"HOME"`, `"ENTER"`).
* **Returns:** Confirmation JSON.

---

## 3. System Tools (ADB & Application Management)

### `execute_adb_shell`
* **Description:** Executes a raw shell command directly on the connected Android device. If execution takes more than 1 second, it returns immediately with `status: "running"` and a `task_id`. Use `shell_receive` to check status and retrieve outputs.
* **Parameters:**
  * `command` (string, required): Command string to execute.
* **Returns:** Completed execution result OR a task token:
  * Fast execution: `{"status": "completed", "exit_code": X, "stdout": "...", "stderr": "..."}` (outputs are truncated to the last 4KB if they exceed it).
  * Long execution: `{"status": "running", "task_id": "UUID-string"}`.

### `shell_receive`
* **Description:** Polls and retrieves the execution status and output of a background shell task initiated by `execute_adb_shell`.
* **Parameters:**
  * `task_id` (string, required): The task ID returned by `execute_adb_shell`.
* **Returns:** JSON containing `status` (`running`, `completed`, `failed`), and output fields (`stdout`, `stderr`, `exit_code`) once finished. Outputs are capped to the latest 4KB.

### `open_settings`
* **Description:** Launches a target Android system settings panel via standard Intents.
* **Parameters:**
  * `panel` (string, required): Target settings panel (options: `ROOT`, `SECURITY`, `WIFI`, `APP_DETAILS`, `DEVELOPER`).
  * `package_name` (string, optional): Required only when `panel` is set to `APP_DETAILS`.
* **Returns:** Confirmation JSON.


### `push_file`
* **Description:** Transports a file from the Host PC to the Android device filesystem (behaves like `adb push`).
* **Parameters:**
  * `host_path` (string, required): Path on host machine.
  * `device_path` (string, required): Destination path on device.
* **Returns:** Status message.

### `pull_file`
* **Description:** Transports a file from the Android device to the Host PC filesystem (behaves like `adb pull`).
* **Parameters:**
  * `device_path` (string, required): File path on device.
  * `host_path` (string, required): Destination path on host.
* **Returns:** Status message.

### `install_app`
* **Description:** Installs an APK file from the host machine onto the device (behaves like `adb install`).
* **Parameters:**
  * `apk_path` (string, required): Absolute path to host APK.
  * `reinstall` (boolean, optional, default: `true`): Flag to include `-r` option.
* **Returns:** Result string (e.g., "Success").

### `uninstall_app`
* **Description:** Removes a package from the device (behaves like `adb uninstall`).
* **Parameters:**
  * `package_name` (string, required): Target application package ID.
  * `keep_data` (boolean, optional, default: `false`): Flag to include `-k` option.
* **Returns:** Result string (e.g., "Success").

---

## 4. Observe Tools (Diagnostics & Logs)

### `get_logcat`
* **Description:** Fetches filtered logcat records from local host stream (100x faster with zero process-spawning overhead, highly recommended over running raw `adb shell logcat` via `execute_adb_shell`). Crucial for extracting target app errors without polluting LLM token context.
* **Parameters:**
  * `tags` (string array, optional, default: `[]`): Filter logs matching specific tags.
  * `level` (string, optional, default: `"V"`): Minimum severity level threshold (`V`, `D`, `I`, `W`, `E`, `F`).
  * `grep_pattern` (string, optional, default: `""`): Regular expression filter pattern.
  * `max_lines` (int, optional, default: `100`): Maximum lines to return.
* **Returns:** Log records as plain text.

### `cleanup_agent`
* **Description:** Force-stops and clears status logs of the device-side UI Automation Agent (Mutton Agent). Essential for self-healing when UiAutomation becomes unresponsive (`rootInActiveWindow returned null`).
* **Parameters:** None.
* **Returns:** Success status message.

### `get_agent_version`
* **Description:** Retrieves the version string and build timestamp of the running Mutton Agent.
* **Parameters:** None.
* **Returns:** Version string in "1.0.0(yyyyMMdd-HH:mm:ss)" format.

---

## 5. Test Automation Control Tools

### `junit_test_reload`
* **Description:** Scans the core plugin directory and hot-reloads compiled JUnit test JAR packages.
* **Parameters:** None.
* **Returns:** Reload status and loaded class definitions.

### `junit_test_list`
* **Description:** Lists all loaded executable JUnit classes and methods.
* **Parameters:** None.
* **Returns:** JSON list of test signatures.

### `junit_test_execute`
* **Description:** Spawns a background thread to execute the specified JUnit test class or individual test method.
* **Parameters:**
  * `class_name` (string, required): Full package class name.
  * `method_name` (string, optional): Specific test method name.
* **Returns:** Execution initialization status.

### `junit_test_receive`
* **Description:** Streams test suite updates, log summaries, and final result arrays (assert logs, failures, stacks) from the running execution task.
* **Parameters:**
  * `last_log_index` (int, optional, default: `0`): Offset index to retrieve incremental updates.
* **Returns:** Run status, incremental execution logs, and final PASS/FAIL summary statistics.

---

## 6. Health & Diagnostics Tools

### `check_testbed_health`
* **Description:** Runs a self-check evaluation across ADB bridges, agent status, and plugin compilation states.
* **Parameters:** None.
* **Returns:** Diagnostics summary JSON containing `adbIsValid`, `deviceSerial`, `deviceInfo`, etc.
