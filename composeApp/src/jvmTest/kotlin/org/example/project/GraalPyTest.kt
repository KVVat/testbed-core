package org.example.project

import org.graalvm.polyglot.Context
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GraalPyTest {

    private fun ensureDeviceReady() {
        var count = 0
        while (count < 45) {
            try {
                val p = ProcessBuilder("adb", "shell", "getprop", "sys.boot_completed").start()
                if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    val out = p.inputStream.bufferedReader().readText().trim()
                    if (out == "1") return
                }
            } catch (_: Exception) {}
            Thread.sleep(1000)
            count++
        }
    }

    @Test
    fun testBasicPythonExecutionAndOutputCapture() {
        val outStream = ByteArrayOutputStream()
        val errStream = ByteArrayOutputStream()

        Context.newBuilder("python")
            .out(outStream)
            .err(errStream)
            .allowAllAccess(true)
            .build().use { context ->
                // Execute basic python script
                val script = """
                    import sys
                    print("Hello from GraalPy! Python version:", sys.version_info.major, sys.version_info.minor)
                    result = sum([1, 2, 3, 4, 5])
                    print(f"Sum result: {result}")
                """.trimIndent()

                context.eval("python", script)
            }

        val output = outStream.toString()
        println("GraalPy Output:\n$output")
        assertTrue(output.contains("Hello from GraalPy!"))
        assertTrue(output.contains("Sum result: 15"))
    }

    @Test
    fun testPolyglotInteropAndShellAlternative() {
        val outStream = ByteArrayOutputStream()

        Context.newBuilder("python")
            .out(outStream)
            .allowAllAccess(true)
            .allowHostAccess(org.graalvm.polyglot.HostAccess.ALL)
            .build().use { context ->
                // Pass variables from Kotlin to Python
                val bindings = context.getBindings("python")
                bindings.putMember("device_serial", "emulator-5554")
                bindings.putMember("target_port", 8080)

                // Run python script that acts like a shell script
                val script = """
                    import os
                    import sys
                    
                    # Read injected Kotlin variables
                    print(f"Target Device: {device_serial}")
                    print(f"Target Port: {target_port}")
                    
                    # Environment & OS operations
                    current_dir = os.getcwd()
                    print(f"Current Directory: {current_dir}")
                    
                    # Return value to Kotlin
                    computed_value = f"CONFIG_{device_serial}_{target_port}"
                """.trimIndent()

                context.eval("python", script)
                val computed = bindings.getMember("computed_value").asString()
                assertEquals("CONFIG_emulator-5554_8080", computed)
            }

        val output = outStream.toString()
        println("Interop Output:\n$output")
        assertTrue(output.contains("Target Device: emulator-5554"))
    }

    @Test
    fun testUnittestOrPytestExecution() {
        val outStream = ByteArrayOutputStream()
        val errStream = ByteArrayOutputStream()

        Context.newBuilder("python")
            .out(outStream)
            .err(errStream)
            .allowAllAccess(true)
            .build().use { context ->
                // Test running standard python unittest framework (built-in without pip)
                val testScript = """
                    import unittest
                    import io
                    import sys

                    class SampleDeviceSecurityTest(unittest.TestCase):
                        def test_tls_configuration(self):
                            self.assertEqual(1 + 1, 2)

                        def test_crypto_key_length(self):
                            key_length = 256
                            self.assertGreaterEqual(key_length, 256)

                    suite = unittest.TestLoader().loadTestsFromTestCase(SampleDeviceSecurityTest)
                    runner = unittest.TextTestRunner(stream=sys.stdout, verbosity=2)
                    result = runner.run(suite)
                    print(f"Tests Run: {result.testsRun}, Errors: {len(result.errors)}, Failures: {len(result.failures)}")
                """.trimIndent()

                context.eval("python", testScript)
            }

        val output = outStream.toString()
        println("Unittest Output:\n$output")
        assertTrue(output.contains("Tests Run: 2"))
        assertTrue(output.contains("OK") || output.contains("test_crypto_key_length"))
    }

    @Test
    fun testHostObjectCallFromPython() {
        val outStream = ByteArrayOutputStream()

        class DeviceController {
            fun executeAdb(cmd: String): String {
                return "SUCCESS: executed '$cmd' on device"
            }
        }

        Context.newBuilder("python")
            .out(outStream)
            .allowAllAccess(true)
            .allowHostAccess(org.graalvm.polyglot.HostAccess.ALL)
            .option("engine.WarnInterpreterOnly", "false")
            .build().use { context ->
                val bindings = context.getBindings("python")
                bindings.putMember("controller", DeviceController())

                val script = """
                    result = controller.executeAdb("shell getprop ro.build.version.release")
                    print(f"Controller response: {result}")
                """.trimIndent()

                context.eval("python", script)
            }

        val output = outStream.toString()
        println("Host Interop Output:\n$output")
        assertTrue(output.contains("SUCCESS: executed 'shell getprop ro.build.version.release' on device"))
    }

    @Test
    fun testPythonRunnerHelper() {
        val capturedLines = mutableListOf<String>()
        val result = org.example.project.python.PythonRunner.runCode(
            code = """
                print("Line 1 from helper")
                print("Line 2 from helper")
            """.trimIndent(),
            onStdoutLine = { capturedLines.add(it) }
        )

        assertTrue(result.success)
        assertEquals(2, capturedLines.size)
        assertEquals("Line 1 from helper", capturedLines[0])
        assertEquals("Line 2 from helper", capturedLines[1])
    }

    @Test
    fun testPythonTestScanner() {
        val testFile = java.io.File("resources/pytest/test_sample_security.py")
        // If running from composeApp or project root, resolve path
        val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_sample_security.py")
        assertTrue(resolvedFile.exists(), "Sample test script should exist at ${resolvedFile.absolutePath}")

        val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
        kotlin.test.assertNotNull(plugin)
        assertEquals("Python Test", plugin.category)
        assertEquals("Sample Cryptographic & Hash Test", plugin.title)
        assertTrue(plugin.isPython)
        assertTrue(plugin.methods.contains("test_sha256_digest"))
        assertTrue(plugin.methods.contains("test_sha512_digest"))
        assertTrue(plugin.methods.contains("test_key_length_validation"))
    }

    @Test
    fun testPythonTestExecutor() {
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_py_test").toFile()
        try {
            val pytestDir = java.io.File(tempBaseDir, "resources/pytest").apply { mkdirs() }
            val script = java.io.File(pytestDir, "test_demo.py").apply {
                writeText("""
                    import unittest

                    CATEGORY = "MDFPP_DEMO"
                    TITLE = "Demo Python Test"
                    DESCRIPTION = "Demo test for executor verification"

                    class DemoTest(unittest.TestCase):
                        def test_pass_1(self):
                            self.assertEqual(10 * 2, 20)

                        def test_pass_2(self):
                            self.assertTrue(True)
                """.trimIndent())
            }

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(script)
            kotlin.test.assertNotNull(plugin)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            // Wait for execution to finish (it runs on Dispatchers.IO)
            var count = 0
            while (executor.isRunning.value && count < 100) {
                Thread.sleep(100)
                count++
            }
            Thread.sleep(200)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty(), "JUnit XML report should be generated in results dir ${resultsDir.absolutePath}")

            val xmlContent = xmlFiles[0].readText()
            println("Generated XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"2\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_pass_1"))
            assertTrue(xmlContent.contains("test_pass_2"))
            assertTrue(xmlContent.contains("<properties>"), "Report must contain <properties> section")
            assertTrue(xmlContent.contains("SFR.name"), "Report must contain SFR.name property")
            assertTrue(xmlContent.contains("<system-out>"), "Report must contain <system-out> section")
            assertTrue(xmlContent.contains("<system-err>"), "Report must contain <system-err> section")
            assertEquals(2, executor.mcpTestResults.size)

            val htmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".html") } ?: emptyArray()
            assertTrue(htmlFiles.isNotEmpty(), "HTML report should be generated in results dir")
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }

    @Test
    fun testSampleSecurityScriptExecution() {
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_sample_sec").toFile()
        try {
            val testFile = java.io.File("resources/pytest/test_sample_security.py")
            val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_sample_security.py")
            assertTrue(resolvedFile.exists())

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
            kotlin.test.assertNotNull(plugin)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            var count = 0
            while (executor.isRunning.value && count < 100) {
                Thread.sleep(100)
                count++
            }
            Thread.sleep(200)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty())

            val xmlContent = xmlFiles[0].readText()
            println("Sample Security XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"3\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_sha256_digest"))
            assertTrue(xmlContent.contains("test_sha512_digest"))
            assertTrue(xmlContent.contains("test_key_length_validation"))
            assertTrue(xmlContent.contains("Python Test"))
            assertEquals(3, executor.mcpTestResults.size)
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }

    @Test
    fun testConnectedDeviceMdfppVerification() {
        ensureDeviceReady()
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_dev_sec").toFile()
        try {
            val testFile = java.io.File("resources/pytest/test_device_mdfpp_verification.py")
            val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_device_mdfpp_verification.py")
            assertTrue(resolvedFile.exists())

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
            kotlin.test.assertNotNull(plugin)
            assertEquals(6, plugin.methods.size)
            assertEquals("Python Test", plugin.category)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            var count = 0
            while (executor.isRunning.value && count < 200) {
                Thread.sleep(100)
                count++
            }
            Thread.sleep(200)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty())

            val xmlContent = xmlFiles[0].readText()
            println("Connected Device MDFPP Verification XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"6\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_01_device_connection_and_boot"))
            assertTrue(xmlContent.contains("test_02_os_integrity_and_security_patch"))
            assertTrue(xmlContent.contains("test_03_file_based_encryption_state"))
            assertTrue(xmlContent.contains("test_04_selinux_enforcement_status"))
            assertTrue(xmlContent.contains("test_05_system_clock_synchronization"))
            assertTrue(xmlContent.contains("test_06_platform_framework_package_manager"))
            assertEquals(6, executor.mcpTestResults.size)
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }

    @Test
    fun testAppUpdateSignatureVerification() {
        ensureDeviceReady()
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_app_update").toFile()
        try {
            val testFile = java.io.File("resources/pytest/test_app_update_signature_mdfpp.py")
            val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_app_update_signature_mdfpp.py")
            assertTrue(resolvedFile.exists())

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
            kotlin.test.assertNotNull(plugin)
            assertEquals(5, plugin.methods.size)
            assertEquals("Python Test", plugin.category)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            var count = 0
            while (executor.isRunning.value && count < 600) {
                Thread.sleep(100)
                count++
            }
            Thread.sleep(300)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty(), "XML report file should be generated in results folder")

            val xmlContent = xmlFiles[0].readText()
            println("App Update Signature Verification XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"5\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_01_install_initial_version_v1"))
            assertTrue(xmlContent.contains("test_02_reject_mismatched_signature_update"))
            assertTrue(xmlContent.contains("test_03_reject_unsigned_update"))
            assertTrue(xmlContent.contains("test_04_accept_valid_signature_update_v2"))
            assertTrue(xmlContent.contains("test_05_cleanup_installed_test_package"))
            assertEquals(5, executor.mcpTestResults.size)
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }

    @Test
    fun testFdpDarDirectBootEncryption() {
        ensureDeviceReady()
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_directboot").toFile()
        try {
            val testFile = java.io.File("resources/pytest/test_fdp_dar_direct_boot_encryption.py")
            val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_fdp_dar_direct_boot_encryption.py")
            assertTrue(resolvedFile.exists())

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
            kotlin.test.assertNotNull(plugin)
            assertEquals(5, plugin.methods.size)
            assertEquals("Python Test", plugin.category)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            var count = 0
            while (executor.isRunning.value && count < 2000) { // allow up to 200s for reboot & boot complete
                Thread.sleep(100)
                count++
            }
            Thread.sleep(300)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty(), "XML report file should be generated in results folder")

            val xmlContent = xmlFiles[0].readText()
            println("FDP_DAR Direct Boot Encryption XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"5\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_01_install_directboot_test_agent"))
            assertTrue(xmlContent.contains("test_02_initialize_storage_and_launch"))
            assertTrue(xmlContent.contains("test_03_reboot_device_into_bfu_state"))
            assertTrue(xmlContent.contains("test_04_verify_directboot_bfu_storage_isolation"))
            assertTrue(xmlContent.contains("test_05_cleanup_test_package"))
            assertEquals(5, executor.mcpTestResults.size)
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }

    @Test
    fun testFdpAcfSandboxIsolation() {
        ensureDeviceReady()
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_sandbox").toFile()
        try {
            val testFile = java.io.File("resources/pytest/test_fdp_acf_sandbox_isolation.py")
            val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_fdp_acf_sandbox_isolation.py")
            assertTrue(resolvedFile.exists())

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
            kotlin.test.assertNotNull(plugin)
            assertEquals(4, plugin.methods.size)
            assertEquals("Python Test", plugin.category)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            var count = 0
            while (executor.isRunning.value && count < 600) {
                Thread.sleep(100)
                count++
            }
            Thread.sleep(300)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty(), "XML report file should be generated in results folder")

            val xmlContent = xmlFiles[0].readText()
            println("FDP_ACF Sandbox Isolation XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"4\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_01_install_target_and_prepare_data"))
            assertTrue(xmlContent.contains("test_02_verify_uninstall_data_destruction"))
            assertTrue(xmlContent.contains("test_03_verify_attacker_sandbox_isolation"))
            assertTrue(xmlContent.contains("test_04_cleanup_sandbox_test_packages"))
            assertEquals(4, executor.mcpTestResults.size)
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }

    @Test
    fun testFdpDarAsymmetricEncryptionLocked() {
        ensureDeviceReady()
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_asym_enc").toFile()
        try {
            val testFile = java.io.File("resources/pytest/test_fdp_dar_asymmetric_encryption_locked.py")
            val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_fdp_dar_asymmetric_encryption_locked.py")
            assertTrue(resolvedFile.exists())

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
            kotlin.test.assertNotNull(plugin)
            assertEquals(5, plugin.methods.size)
            assertEquals("Python Test", plugin.category)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            var count = 0
            while (executor.isRunning.value && count < 600) {
                Thread.sleep(100)
                count++
            }
            Thread.sleep(300)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty(), "XML report file should be generated in results folder")

            val xmlContent = xmlFiles[0].readText()
            println("FDP_DAR Asymmetric Encryption Locked XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"5\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_01_install_encryption_agent"))
            assertTrue(xmlContent.contains("test_02_asymmetric_encryption_while_locked"))
            assertTrue(xmlContent.contains("test_03_udr_private_key_blocked_while_locked"))
            assertTrue(xmlContent.contains("test_04_udr_private_key_release_after_pin_unlock"))
            assertTrue(xmlContent.contains("test_05_cleanup_encryption_package"))
            assertEquals(5, executor.mcpTestResults.size)
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }

    @Test
    fun testSampleIdiomaticMdfpp() {
        ensureDeviceReady()
        val tempBaseDir = java.nio.file.Files.createTempDirectory("testbed_idiomatic").toFile()
        try {
            val testFile = java.io.File("resources/pytest/test_sample_idiomatic_mdfpp.py")
            val resolvedFile = if (testFile.exists()) testFile else java.io.File("composeApp/resources/pytest/test_sample_idiomatic_mdfpp.py")
            assertTrue(resolvedFile.exists())

            val plugin = org.example.project.python.PythonTestScanner.parseTestPlugin(resolvedFile)
            kotlin.test.assertNotNull(plugin)
            assertEquals(2, plugin.methods.size)
            assertEquals("Python Test", plugin.category)

            val executor = org.example.project.python.PythonTestExecutor(tempBaseDir)
            executor.runTest(plugin, isMcp = true)

            var count = 0
            while (executor.isRunning.value && count < 200) {
                Thread.sleep(100)
                count++
            }
            Thread.sleep(300)

            val resultsDir = java.io.File(tempBaseDir, "results")
            val xmlFiles = resultsDir.listFiles { _, name -> name.startsWith("junit-report-") && name.endsWith(".xml") } ?: emptyArray()
            assertTrue(xmlFiles.isNotEmpty(), "XML report file should be generated in results folder")

            val xmlContent = xmlFiles[0].readText()
            println("Idiomatic MDFPP Sample XML Report:\n$xmlContent")
            assertTrue(xmlContent.contains("tests=\"2\""))
            assertTrue(xmlContent.contains("failures=\"0\""))
            assertTrue(xmlContent.contains("test_01_verify_device_security_props"))
            assertTrue(xmlContent.contains("test_02_selinux_and_user_unlock_state"))
            assertEquals(2, executor.mcpTestResults.size)
        } finally {
            tempBaseDir.deleteRecursively()
        }
    }
}
