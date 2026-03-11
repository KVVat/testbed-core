# Idea Notes & Action Plans

## ADB Connection Stability (offline issue)
- **Observation:** Android Studio seems to automatically recover from prolonged `offline` states, likely by executing `kill-server` and `start-server` on its own initialization.
- **Action Plan:**
  - Introduce an automatic or single-click recovery mechanism within `testbed-core`.
  - When the device is detected as `offline` for an extended period, or before a test execution if the adb daemon is unresponsive, automate the `adb kill-server && adb start-server` sequence.
  - Consider exposing a `reset_adb` MCP tool so that the AI or the user via UI Inspector can trigger a clean reconnect easily.

## UIDevice Command Failures on Restart
- **Observation:** `UIDevice` related commands (often used in UI Automator / Android tests) frequently fail immediately after the device restarts.
- **Action Plan:**
  - Need to investigate the exact cause (e.g., `uiautomator` service not being fully ready, or instrumentation crashing during boot).
  - Add a robust wait/retry mechanism for `UIDevice` initialization post-reboot. For example, waiting for `sys.boot_completed` property to be `1`, or having a retry loop with exponential backoff for the first few `UIDevice` interactions.
