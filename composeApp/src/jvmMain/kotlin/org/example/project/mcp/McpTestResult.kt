package org.example.project.mcp

data class McpTestResult(
    val class_name: String,
    val method_name: String,
    val status: String,
    val assertion_msg: String? = null,
    val stacktrace: String? = null
)
