# Mutton Agent

Mutton Agent is an Android test automation helper that runs directly on target devices (physical or emulator) as an **Android Instrumentation Test**. It leverages `UiAutomator` (`UiDevice`) to query UI layout hierarchies, simulate user interface interactions, and stream data to the host.

Unlike standard standalone binaries, running as an instrumentation test allows Mutton Agent to bypass severe OS-level permissions restrictions and directly capture system UI and secure screenshots.

## Building and Packaging

Compile and copy the test APK to the host application's resources directory using the following command from the project root:

```bash
./gradlew -p tools/mutton-agent copyTestApk
```

This task compiles the instrumentation APK and copies it to `composeApp/src/jvmMain/resources/mutton-agent-androidTest.apk` for the host app to deployment.

## Deployment & Execution

TestBed Core's host application automates the deployment loop. If you want to deploy and run it manually:

1. **Install the APK**
   ```bash
   adb install -r -t mutton-agent-androidTest.apk
   ```

2. **Launch the Instrumentation Server**
   Start the instrumentation runner in blocking mode. This starts an abstract socket server on the device:
   ```bash
   adb shell am instrument -w org.example.mutton.test/androidx.test.runner.AndroidJUnitRunner
   ```

---

## Communication Protocol

Mutton Agent listens for connections on a Linux abstract namespace socket named **`mutton_agent`**.
The host PC forwards a port to this socket (e.g. `adb forward tcp:PORT localabstract:mutton_agent`) and exchanges JSON-RPC commands.

### Available Command Schema

#### `ping`
Verify connection health.
* Request:
  ```json
  {"cmd": "ping"}
  ```
* Response:
  ```json
  {"status": "pong", "message": "I am alive!"}
  ```

#### `version`
Get agent build and runtime information.
* Request:
  ```json
  {"cmd": "version"}
  ```
* Response:
  ```json
  {"status": "ok", "version": "1.0.0(build_timestamp)"}
  ```

#### `get_ui_dump`
Retrieve the current UI layout tree. Supports optional screenshot capture.
* Request:
  ```json
  {
    "cmd": "get_ui_dump",
    "include_image": true,
    "image_quality": 4
  }
  ```
  * `include_image` (boolean, optional, default `false`): Capture screenshot.
  * `image_quality` (int, optional, default `2`): Screen compression level (1-4).
* Response:
  ```json
  {
    "type": "dump_result",
    "status": "ok",
    "output": "[UiNode JSON String]",
    "screen_width": 1080,
    "screen_height": 2424,
    "screenshot": "[Base64 JPEG String]" (optional)
  }
  ```

#### `tap`
Perform a click action at the specified coordinate.
* Request:
  ```json
  {
    "cmd": "tap",
    "x": 540,
    "y": 1200
  }
  ```
* Response:
  ```json
  {"status": "ok"}
  ```

#### `swipe`
Perform a drag gesture.
* Request:
  ```json
  {
    "cmd": "swipe",
    "start_x": 100,
    "start_y": 500,
    "end_x": 100,
    "end_y": 100
  }
  ```
* Response:
  ```json
  {"status": "ok"}
  ```

#### `input_text`
Type text into the active text field.
* Request:
  ```json
  {
    "cmd": "input_text",
    "text": "Hello World",
    "press_enter": true
  }
  ```
* Response:
  ```json
  {"status": "ok"}
  ```

#### `press_key`
Simulate a hardware key press.
* Request:
  ```json
  {
    "cmd": "press_key",
    "keycode": "BACK"
  }
  ```
  * `keycode` can be standard Android KeyEvent keycode names (e.g., `"BACK"`, `"HOME"`, `"ENTER"`, `"MENU"`).
* Response:
  ```json
  {"status": "ok"}
  ```

#### `shell`
Execute a local shell command on the device (limited to runner permissions).
* Request:
  ```json
  {
    "cmd": "shell",
    "args": "pm list packages"
  }
  ```
* Response:
  ```json
  {
    "status": "completed",
    "exit_code": 0,
    "stdout": "...",
    "stderr": "..."
  }
  ```

#### `start_stream` / `stop_stream`
Stream base64 screenshots continuously down the active connection.
* Request:
  ```json
  {
    "cmd": "start_stream",
    "fps": 1.0,
    "image_quality": 2
  }
  ```
* Stream Output:
  ```json
  {"type": "stream_frame", "data": "[Base64 JPEG]"}
  ```
* Stop:
  ```json
  {"cmd": "stop_stream"}
  ```

#### `start_dump_stream` / `stop_dump_stream`
Stream UI hierarchy trees continuously down the active connection.
* Request:
  ```json
  {
    "cmd": "start_dump_stream",
    "fps": 0.5
  }
  ```
* Stream Output:
  ```json
  {"type": "dump_stream_frame", "data": "[UiNode JSON String]"}
  ```
* Stop:
  ```json
  {"cmd": "stop_dump_stream"}
  ```
