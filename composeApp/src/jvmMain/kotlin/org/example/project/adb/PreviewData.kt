package org.example.project.adb

data class PreviewData(
    val isBinary: Boolean,
    val textContent: String?,
    val hexDumpLines: List<String>? = null,
    val fileType: String? = null
)
