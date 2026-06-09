# TestBed Core: Model Context Protocol (MCP) Specification

This specification defines the complete set of MCP tools exposed by TestBed Core to enable LLM agents (such as Antigravity) to perform sensing, execution, adb system diagnostics, and JUnit test automation on target Android devices.

---

## 1. Sensing Tools (Environment Observability)

### `get_ui_dump`
* **Description:** Retrieves the current Android on-screen UI hierarchy (excluding invisible nodes) and optionally captures a compressed screenshot.
* **Parameters:**
  * `include_image` (boolean, optional, default: `false`): If true, returns a Base64-encoded WebP screenshot.
  * `image_quality` (int, optional, default: `2`): Image compression factor (1 = 100%, 2 = 50%, 3 = 33%, 4 = 25%). Higher factors are recommended to minimize token consumption.
* **Returns:** JSON containing `json_dump` (XML-like hierarchy nodes) and `screenshot`.

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

All action tools automatically block and wait for UI idle state before returning. Upon completion, they yield the latest interactable UI dump summary (clickable/scrollable elements) to minimize agent iteration loops.

### `tap`
* **Description:** Taps the screen at specified physical coordinates `(x, y)`.
* **Parameters:**
  * `x` (int, required): X coordinate.
  * `y` (int, required): Y coordinate.
* **Returns:** Updated `get_ui_dump` summary JSON.

### `input_text`
* **Description:** Enters text into the currently focused input field.
* **Parameters:**
  * `text` (string, required): The string to type. Spaces are automatically escaped.
  * `press_enter` (boolean, optional, default: `true`): If true, fires a virtual Enter key press after entering text.
* **Returns:** Updated `get_ui_dump` summary JSON.

### `swipe`
* **Description:** Drags across the screen from start coordinates to end coordinates.
* **Parameters:**
  * `start_x` (int, required)
  * `start_y` (int, required)
  * `end_x` (int, required)
  * `end_y` (int, required)
* **Returns:** Updated `get_ui_dump` summary JSON.

### `press_key`
* **Description:** Fires a physical or virtual system key event.
* **Parameters:**
  * `keycode` (string, required): Keycode alias (e.g., `"BACK"`, `"HOME"`, `"ENTER"`).
* **Returns:** Updated `get_ui_dump` summary JSON.

---

## 3. System Tools (ADB & Application Management)

### `execute_adb_shell`
* **Description:** Executes a raw shell command directly on the connected Android device.
* **Parameters:**
  * `command` (string, required): Command string to execute (e.g., `"pm list packages"`).
* **Returns:** Standard output (`stdout`) and standard error (`stderr`) responses.

### `open_settings`
* **Description:** Launches a target Android system settings panel via standard Intents.
* **Parameters:**
  * `panel` (string, required): Target settings panel (options: `ROOT`, `SECURITY`, `WIFI`, `APP_DETAILS`, `DEVELOPER`).
  * `package_name` (string, optional): Required only when `panel` is set to `APP_DETAILS`.
* **Returns:** Execution status and the updated UI dump.

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

### `clear_logcat`
* **Description:** Clears the system logcat buffer (behaves like `adb logcat -c`).
* **Parameters:** None.
* **Returns:** Success status message.

### `get_logcat`
* **Description:** Fetches filtered logcat records. Crucial for extracting target app errors without polluting LLM token context.
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
