package org.example.project.model

sealed class TimelineItem {
    abstract val id: String
    abstract val timestamp: Long

    // Single unified record for both polling and actions
    data class Record(
        override val id: String,
        override val timestamp: Long,
        val snapshot: Snapshot,
        val hasChange: Boolean,                  // True if UI state changed or an action occurred
        val eventLabel: String? = null,          // "Poll", "Tap at (x,y)"
        val actionDetails: ActionDetails? = null // Details if this was driven by a user action
    ) : TimelineItem()
}

data class ActionDetails(
    val command: String,
    val args: Map<String, Any>
)

data class Snapshot(
    val timestamp: Long,               // Specific time when this snapshot was pulled
    val jsonDump: String,              // Full UI dump JSON
    val screenshotBase64: String       // JPG 25% compressed Base64 string
)
