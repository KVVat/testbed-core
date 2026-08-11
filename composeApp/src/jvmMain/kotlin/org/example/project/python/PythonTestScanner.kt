package org.example.project.python

import org.example.project.TestPlugin
import java.io.File

object PythonTestScanner {

    private val TEST_METHOD_REGEX = Regex("""^\s*def\s+(test_[a-zA-Z0-9_]+)\s*\(""", RegexOption.MULTILINE)
    private val SFR_CATEGORY_REGEX = Regex("""(?:SFR_CATEGORY|CATEGORY)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val SFR_TITLE_REGEX = Regex("""(?:SFR_TITLE|TITLE)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val SFR_DESC_REGEX = Regex("""(?:SFR_DESCRIPTION|DESCRIPTION)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val COMMENT_CATEGORY_REGEX = Regex("""#\s*Category:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val COMMENT_TITLE_REGEX = Regex("""#\s*Title:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val COMMENT_DESC_REGEX = Regex("""#\s*Description:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val DOCSTRING_REGEX = Regex("""^["']{3}([\s\S]*?)["']{3}""", RegexOption.MULTILINE)

    fun scanDirectory(dir: File): List<TestPlugin> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val pythonFiles = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "py" }
            .filter { isTestFile(it) }
            .toList()

        return pythonFiles.mapNotNull { parseTestPlugin(it) }
    }

    private fun isTestFile(file: File): Boolean {
        val name = file.name.lowercase()
        val parent = file.parentFile?.name?.lowercase() ?: ""
        return name.startsWith("test_") || name.endsWith("_test.py") || parent == "tests" || parent == "pytest"
    }

    fun parseTestPlugin(file: File): TestPlugin? {
        try {
            val content = file.readText()
            val shortName = file.nameWithoutExtension

            // Find all test methods
            val methodMatches = TEST_METHOD_REGEX.findAll(content).map { it.groupValues[1] }.distinct().toList()
            val methods = if (methodMatches.isNotEmpty()) methodMatches else listOf("run_all")

            // Extract metadata
            var title = SFR_TITLE_REGEX.find(content)?.groupValues?.get(1)?.trim()
                ?: COMMENT_TITLE_REGEX.find(content)?.groupValues?.get(1)?.trim()
                ?: formatShortNameToTitle(shortName)

            var description = SFR_DESC_REGEX.find(content)?.groupValues?.get(1)?.trim()
                ?: COMMENT_DESC_REGEX.find(content)?.groupValues?.get(1)?.trim()
                ?: DOCSTRING_REGEX.find(content)?.groupValues?.get(1)?.trim()
                ?: "Python Test Suite ($shortName.py)"

            var category = SFR_CATEGORY_REGEX.find(content)?.groupValues?.get(1)?.trim()
                ?: COMMENT_CATEGORY_REGEX.find(content)?.groupValues?.get(1)?.trim()
                ?: "Python Test"

            val parentDirName = file.parentFile?.name ?: "pytest"

            return TestPlugin(
                id = "py_${parentDirName}_$shortName",
                name = "[$parentDirName] $shortName",
                shortName = shortName,
                title = title,
                description = description,
                category = category,
                methods = methods,
                isPython = true,
                scriptFile = file
            )
        } catch (e: Exception) {
            System.err.println("Failed to parse Python test file [${file.absolutePath}]: ${e.message}")
            return null
        }
    }

    private fun formatShortNameToTitle(shortName: String): String {
        return shortName.removePrefix("test_").removeSuffix("_test")
            .split('_', '-')
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }
}
