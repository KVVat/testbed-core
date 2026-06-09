# Timeline Inspector Specification and Implementation Plan

## 1. UI Layout (Video-Editor-like Timeline & Pin Layout)

The UI follows a layout inspired by video editor timelines, emphasizing exact timestamps, duration, and visual navigation.

```
+------------------------------------------------------------------------------------+
| [ 1. Timeline (High-height Time Axis with Pin/Marker UI) ]                         |
|   |         |         o (tap)                       |         o (input_text)       |
|   |         |         |                             |         |                    |
| 11:45:00  11:45:15  11:45:28                      11:45:45  11:45:52               |
+--------------------------+---------------------------------------------------------+
| [ 2. Metadata Panel ]    | [ 3. Main Area (Split View) ]                           |
|                          | +--------------------------+--------------------------+ |
| Selected Step Info:      | |                          |                          | |
| Type: Action (tap)       | | [ Screenshot View ]      | [ UI (DOM) Tree View ]   | |
| Command: tap             | | (Bounds Overlay)         | - root (LinearLayout)    | |
| Coords: (120, 340)       | |                          |   - button (resource-id) | |
| Start: 11:45:28          | |                          |                          | |
| End:   11:45:30          | |                          |                          | |
| [Before] | >[After]      | |                          |                          | |
+--------------------------+---------------------------------------------------------+
```

### ① Top: Timeline (TimelineBar)
*   **Visual**: A time axis with ticks (vertical lines) showing time markers (HH:mm:ss).
*   **Pins (Markers)**: Events (Capture or Action) are plotted as vertical pins: a vertical line with a circular head.
    *   **Capture (Polling)**: Neutral color pin (e.g. Gray).
    *   **Action (User/LLM command)**: Distinct color (e.g. Blue or Green), potentially showing a horizontal bracket connecting `before` and `after` timestamps to represent action duration.
*   **Behavior**:
    *   If no items have been polled/recorded yet, the timeline is completely empty.
    *   Items scroll horizontally as new events arrive.
    *   Clicking a pin updates the active snapshot and UI views.

### ② Left: Metadata Panel
*   **Visual**: Displays timestamp, action type, command arguments, and time duration for Actions.
*   **Comparison Toggle**: Displays `[Before]` and `[After]` toggles for Actions to switch the main canvas between the action's pre-state and post-state.

### ③ Center/Right: Main Area (Split View)
*   **Left (Screenshot View)**: Screenshot representing the selected snapshot, highlighted with bounding boxes.
*   **Right (UI Tree View)**: Interactive DOM hierarchy of the selected snapshot.
*   **Node Details Popup**: Double-clicking or clicking an Info icon next to a node displays detailed properties in a dialog popup.

*   *(Note: The old Left Pane Toolbar buttons like "Ping Agent" and "Dump UI Tree" are removed because the tab auto-polls on display).*

---

## 2. Data Structure

### ① Kotlin Data Model
The models are designed to capture precise timestamps for each snapshot.

```kotlin
package org.example.project.model

sealed class TimelineItem {
    abstract val id: String
    abstract val timestamp: Long       // Represents start time of the item

    // 1. Single snapshot captured by automatic background polling
    data class Capture(
        override val id: String,
        override val timestamp: Long,
        val snapshot: Snapshot
    ) : TimelineItem()

    // 2. Action capturing before/after states with separate timestamps
    data class Action(
        override val id: String,
        override val timestamp: Long,       // Action start (before.timestamp)
        val endTimestamp: Long,             // Action end (after.timestamp)
        val command: String,
        val args: Map<String, Any>,
        val before: Snapshot?,              // State before command execution
        val after: Snapshot                 // State after command execution
    ) : TimelineItem()
}

data class Snapshot(
    val timestamp: Long,               // Specific time when this snapshot was pulled
    val jsonDump: String,              // Full UI dump JSON
    val screenshotBase64: String       // JPG 25% compressed Base64 string
)
```

### ② History Policy & Buffer
*   **Automatic Polling**: Captures every 15 seconds. Ring buffer of 100 elements.
*   **Change Detection**: Avoid duplicate captures if the UI dump JSON string is identical to the previous snapshot.

---

## 3. Implementation Roadmap

### 🏁 Phase 1 (First Step): Polling, Ring Buffer, and Pin Timeline UI
*   Update data models (`TimelineItem`, `Snapshot`) to store timestamps inside snapshots.
*   Remove old buttons ("Ping Agent", "Dump UI Tree") from the sidebar when selectedTab is 1.
*   Implement the custom Pin Timeline UI showing HH:mm:ss text labels under vertical pins.
*   Make the timeline empty when there are no items.

### 🏃 Phase 2: Action Integration (Before / After Comparison)
*   Store timestamps for before and after states in `TimelineItem.Action`.
*   Support `[Before]` and `[After]` toggling and display action duration inside Metadata Panel.
