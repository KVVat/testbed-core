#!/usr/bin/env python3
import sys
import json
import threading
import time
import urllib.request
import urllib.error

KTOR_MCP_URL = "http://localhost:11452/mcp"
session_url = None
sse_connected = False
lock = threading.Lock()

LOG_FILE = "/tmp/mcp_bridge_debug.log"

def debug_log(msg):
    try:
        with open(LOG_FILE, "a") as f:
            f.write(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}\n")
    except Exception:
        pass

STATIC_TOOLS = [
    {
        "name": "check_testbed_health",
        "description": "Check ADB connection, device online status, and agent status all at once.",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "cleanup_agent",
        "description": "Force-stops the agent process on the device for cleanup. Useful for recovery when UiAutomation errors occur.",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "junit_test_reload",
        "description": "Reloads the test JAR.",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "junit_test_list",
        "description": "Returns a JSON array of the currently loaded tests.",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "junit_test_execute",
        "description": "Starts execution of the specified test class or method.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "class_name": {"type": "string", "description": "Fully qualified test class name"},
                "method_name": {"type": "string", "description": "Optional: specific test method name"}
            },
            "required": ["class_name"]
        }
    },
    {
        "name": "junit_test_receive",
        "description": "Retrieves test execution results (Pass/Fail/Error info, stacktrace, etc) in JSON. Returns interim logs and progress if Running.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "last_log_index": {"type": "integer", "description": "Index to retrieve only new logs since last call. Default 0."}
            }
        }
    },
    {
        "name": "start_stream",
        "description": "Start screenshot stream. Optional parameters: 'fps' (Float, default 1.0) and 'image_quality' (Int: 1=100% size/80% jpeg, 2=50% size/50% jpeg, 3=33% size/33% jpeg, 4=25% size/20% jpeg. Default is 2).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "fps": {"type": "number", "description": "Frames per second. Default 1.0"},
                "image_quality": {"type": "integer", "description": "1=100%/80%jpeg, 2=50%/50%jpeg, 3=33%/33%jpeg, 4=25%/20%jpeg. Default 2"}
            }
        }
    },
    {
        "name": "stop_stream",
        "description": "Stop screenshot stream",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "ping",
        "description": "Ping the mutton agent",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "get_ui_dump",
        "description": "Retrieves the current UI hierarchy. Default format is 'summary' (compact flat list optimized for LLMs, ~1KB). Use format='json' for full tree with optional Base64 screenshot.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "format": {"type": "string", "description": "Output format: 'summary' (compact flat list, default) or 'json' (full tree)"},
                "include_image": {"type": "boolean", "description": "Include screenshot. With format='summary', returns TextContent + ImageContent together. Default false"},
                "image_quality": {"type": "integer", "description": "1=100%, 2=50%, 3=33%, 4=25%. Default 4 (25%)"}
            }
        }
    },
    {
        "name": "get_agent_version",
        "description": "Retrieves the version information of the Testbed agent (Mutton Agent).",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "tap",
        "description": "Physically taps the specified (x, y) coordinates. *Automatically waits for idle and returns the latest UI dump after execution.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate to tap"},
                "y": {"type": "integer", "description": "Y coordinate to tap"}
            },
            "required": ["x", "y"]
        }
    },
    {
        "name": "input_text",
        "description": "Inputs text into the currently focused input field. *Automatically waits for idle and returns the latest UI dump after execution.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to input (ASCII only)"},
                "press_enter": {"type": "boolean", "description": "Press Enter after input. Default true"}
            },
            "required": ["text"]
        }
    },
    {
        "name": "swipe",
        "description": "Swipes (scrolls) the screen between the specified coordinates. *Automatically waits for idle and returns the latest UI dump after execution.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "start_x": {"type": "integer", "description": "Start X coordinate"},
                "start_y": {"type": "integer", "description": "Start Y coordinate"},
                "end_x": {"type": "integer", "description": "End X coordinate"},
                "end_y": {"type": "integer", "description": "End Y coordinate"}
            },
            "required": ["start_x", "start_y", "end_x", "end_y"]
        }
    },
    {
        "name": "press_key",
        "description": "Sends a physical or system key event. *Automatically waits for idle and returns the latest UI dump after execution.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "keycode": {"type": "string", "description": "Key name: HOME, BACK, ENTER, POWER, VOLUME_UP, VOLUME_DOWN, etc. Or numeric keycode as string."}
            },
            "required": ["keycode"]
        }
    },
    {
        "name": "get_device_info",
        "description": "Retrieves device hardware and OS information (wrapper for getprop).",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "execute_adb_shell",
        "description": "Executes an adb shell command directly against the connected device. e.g. ls -l /sdcard",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Shell command to execute"}
            },
            "required": ["command"]
        }
    },
    {
        "name": "get_device_state",
        "description": "Retrieves screen ON/OFF, lock state, and foreground package.",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "open_settings",
        "description": "Opens a specific settings panel on the device.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "panel": {"type": "string", "description": "Panel name: ROOT, SECURITY, WIFI, DEVELOPER, APP_DETAILS"},
                "package_name": {"type": "string", "description": "Required for APP_DETAILS panel"}
            },
            "required": ["panel"]
        }
    },
    {
        "name": "push_file",
        "description": "Pushes a file from the host PC to the device.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host_path": {"type": "string", "description": "Absolute path on host PC"},
                "device_path": {"type": "string", "description": "Absolute path on device"}
            },
            "required": ["host_path", "device_path"]
        }
    },
    {
        "name": "pull_file",
        "description": "Pulls a file from the device to the host PC.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "device_path": {"type": "string", "description": "Absolute path on device"},
                "host_path": {"type": "string", "description": "Absolute path on host PC"}
            },
            "required": ["device_path", "host_path"]
        }
    },
    {
        "name": "install_app",
        "description": "Installs an APK from the host PC to the device.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "apk_path": {"type": "string", "description": "Absolute path to APK on host PC"},
                "reinstall": {"type": "boolean", "description": "Reinstall if already installed. Default true"}
            },
            "required": ["apk_path"]
        }
    },
    {
        "name": "uninstall_app",
        "description": "Uninstalls an app from the device.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "package_name": {"type": "string", "description": "Package name to uninstall"},
                "keep_data": {"type": "boolean", "description": "Keep app data after uninstall. Default false"}
            },
            "required": ["package_name"]
        }
    },
    {
        "name": "clear_logcat",
        "description": "Clears the device's Logcat buffer (adb logcat -c).",
        "inputSchema": {"type": "object", "properties": {}}
    },
    {
        "name": "get_logcat",
        "description": "Retrieves filtered Logcat lines. (Essential for saving tokens)",
        "inputSchema": {
            "type": "object",
            "properties": {
                "tags": {"type": "array", "items": {"type": "string"}, "description": "Log tag names to filter by"},
                "level": {"type": "string", "description": "Minimum log level: V, D, I, W, E, F. Default V"},
                "grep_pattern": {"type": "string", "description": "Grep pattern for filtering"},
                "max_lines": {"type": "integer", "description": "Maximum lines to return. Default 100"},
                "process": {"type": "string", "description": "Filter by process: package name or PID."}
            }
        }
    }
]

def sse_listener_thread():
    global session_url, sse_connected
    while True:
        try:
            debug_log("Attempting to connect to Ktor MCP SSE...")
            req = urllib.request.Request(KTOR_MCP_URL, method="GET")
            with urllib.request.urlopen(req, timeout=5) as response:
                for line_bytes in response:
                    line = line_bytes.decode('utf-8').strip()
                    if not line:
                        continue
                    if line.startswith("data:"):
                        data_content = line[5:].strip()
                        # Detect any session redirection endpoint dynamically
                        if not sse_connected and ("sessionId=" in data_content or "session_id=" in data_content):
                            with lock:
                                session_url = f"http://localhost:11452{data_content}"
                                sse_connected = True
                            debug_log(f"✅ Stdio Bridge: SSE Session Established. Endpoint={session_url}")
                        else:
                            try:
                                payload = json.loads(data_content)
                                if "jsonrpc" in payload:
                                    sys.stdout.write(json.dumps(payload) + "\n")
                                    sys.stdout.flush()
                            except json.JSONDecodeError:
                                pass
        except Exception as e:
            with lock:
                sse_connected = False
                session_url = None
            debug_log(f"⚠️ Stdio Bridge: Ktor connection lost: {str(e)}. Retrying...")
            time.sleep(2)

def main():
    try:
        with open(LOG_FILE, "w") as f:
            f.write("=== MCP Bridge Started ===\n")
    except Exception:
        pass

    # Start the SSE background thread to connect to Ktor server
    t = threading.Thread(target=sse_listener_thread, daemon=True)
    t.start()

    sys.stderr.write("✅ TestBed Core MCP Stdio Bridge Initialized.\n")
    sys.stderr.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            debug_log(f"stdin: {line}")
            req_data = json.loads(line)
            req_id = req_data.get("id")
            method = req_data.get("method")

            # 0. Ignore notification requests (they lack an 'id' and expect no response)
            if req_id is None:
                debug_log(f"Ignored notification: {method}")
                continue

            # 1. Handle initialize locally (static response)
            if method == "initialize":
                resp_data = {
                    "jsonrpc": "2.0",
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {
                            "tools": {"listChanged": True}
                        },
                        "serverInfo": {
                            "name": "testbed-core",
                            "version": "1.0.0"
                        }
                    },
                    "id": req_id
                }
                sys.stdout.write(json.dumps(resp_data) + "\n")
                sys.stdout.flush()
                debug_log(f"stdout (initialize): {json.dumps(resp_data)}")
                continue

            # 2. Handle tools/list locally (static response)
            if method == "tools/list":
                resp_data = {
                    "jsonrpc": "2.0",
                    "result": {
                        "tools": STATIC_TOOLS
                    },
                    "id": req_id
                }
                sys.stdout.write(json.dumps(resp_data) + "\n")
                sys.stdout.flush()
                debug_log(f"stdout (tools/list): {json.dumps(resp_data)}")
                continue

            # 3. Guard for tools/call (wait for session if needed)
            # Give a small 3-second grace period for connection initialization
            wait_limit = 15
            while not sse_connected and wait_limit > 0:
                time.sleep(0.2)
                wait_limit -= 1

            if not sse_connected:
                debug_log("Error: Ktor server unreachable on tool call.")
                err_resp = {
                    "jsonrpc": "2.0",
                    "error": {
                        "code": -32603,
                        "message": "Error: TestBed Core backend Ktor server is unreachable."
                    },
                    "id": req_id
                }
                sys.stdout.write(json.dumps(err_resp) + "\n")
                sys.stdout.flush()
                continue

            # 4. Forward JSON-RPC call via HTTP POST
            post_data = json.dumps(req_data).encode('utf-8')
            req = urllib.request.Request(
                session_url,
                data=post_data,
                headers={"Content-Type": "application/json"},
                method="POST"
            )
            try:
                urllib.request.urlopen(req, timeout=120)
                debug_log(f"Forwarded request {method} (id={req_id})")
            except urllib.error.HTTPError as e:
                err_text = e.read().decode('utf-8')
                try:
                    payload = json.loads(err_text)
                    sys.stdout.write(json.dumps(payload) + "\n")
                    sys.stdout.flush()
                    debug_log(f"Forwarded error response: {err_text}")
                except Exception:
                    sys.stderr.write(f"HTTP Error: {err_text}\n")
                    sys.stderr.flush()

        except Exception as e:
            debug_log(f"Error in main loop: {str(e)}")
            sys.stderr.write(f"Error handling request: {str(e)}\n")
            sys.stderr.flush()

if __name__ == '__main__':
    main()
