package org.example.project.adb

data class AdbFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String,
    val lastModified: String,
    val isSymbolicLink: Boolean,
    val linkTarget: String? = null
)
