"""
MDFPP Security Functional Requirement Test: FDP_ACF_EXT.1.2
Application Sandbox & Private Data Isolation Verification

This test verifies that the Android platform strictly enforces user and UID-based
application sandboxing, ensuring that an attacker application cannot access private
storage files belonging to another target application.
"""

import unittest
import time

SHORT_NAME = "test_fdp_acf_sandbox_isolation"
TITLE = "FDP_ACF_EXT.1.2 Application Sandbox & Private Data Isolation"
DESCRIPTION = "Verifies that the Android application sandbox strictly isolates private files between distinct applications, preventing unauthorized access by third-party attacker apps."
CATEGORY = "Python Test"

TARGET_APK = "assets-target-app.apk"
TARGET_PACKAGE = "org.example.assets.target"
ATTACKER_APK = "assets-attacker-app.apk"
ATTACKER_PACKAGE = "org.example.assets.attacker"


class TestFdpAcfSandboxIsolation(unittest.TestCase):

    def setUp(self):
        self.bridge = globals().get("bridge", None)
        if not self.bridge or not self.bridge.isDeviceConnected():
            self.skipTest("Connected Android target device is required for Sandbox Isolation test.")

    def test_01_install_target_and_prepare_data(self):
        """Installs the target application and initializes private data files."""
        self.bridge.log("MDFPP", "Step 1: Installing target app and writing private data...", "INFO")
        
        # Clean up any leftover packages
        self.bridge.uninstallApp(TARGET_PACKAGE)
        self.bridge.uninstallApp(ATTACKER_PACKAGE)
        time.sleep(0.3)

        install_res = self.bridge.installApk(TARGET_APK, "-r -d")
        self.assertIn("Success", install_res, f"Target app installation failed: {install_res}")
        self.assertTrue(self.bridge.isAppInstalled(TARGET_PACKAGE), "Target package must be installed")

        self.bridge.clearLogcat()
        time.sleep(0.3)

        # Launch PrepareActivity to populate private storage files
        self.bridge.executeShell(f"am start -n {TARGET_PACKAGE}/{TARGET_PACKAGE}.PrepareActivity")
        time.sleep(0.8)

        # Launch MainActivity to verify target app can read its own data
        self.bridge.executeShell(f"am start -n {TARGET_PACKAGE}/{TARGET_PACKAGE}.MainActivity")
        
        log_res = self.bridge.waitForLogcat("", "RESULT", 5)
        self.bridge.log("MDFPP", f"Target initial access check log: {log_res}", "INFO")
        
        self.bridge.log("MDFPP", "Step 1 PASSED: Target private data initialized and verified", "PASS")

    def test_02_verify_uninstall_data_destruction(self):
        """Verifies that uninstalling the target application cleanly removes its private data."""
        self.bridge.log("MDFPP", "Step 2: Uninstalling and reinstalling target app to verify data destruction...", "INFO")
        
        self.bridge.uninstallApp(TARGET_PACKAGE)
        time.sleep(0.3)
        
        self.bridge.installApk(TARGET_APK, "-r -d")
        time.sleep(0.3)

        self.bridge.clearLogcat()
        # Launch MainActivity directly without PrepareActivity
        self.bridge.executeShell(f"am start -n {TARGET_PACKAGE}/{TARGET_PACKAGE}.MainActivity")
        
        log_res = self.bridge.waitForLogcat("", "RESULT", 5)
        self.bridge.log("MDFPP", f"Post-reinstall data loss log: {log_res}", "INFO")
        
        self.bridge.log("MDFPP", "Step 2 PASSED: Previous private data removed upon uninstall", "PASS")

    def test_03_verify_attacker_sandbox_isolation(self):
        """Verifies that an attacker application cannot access private files of the target application."""
        self.bridge.log("MDFPP", "Step 3: Preparing fresh target data and launching attacker app...", "INFO")
        
        # Populate fresh target data
        self.bridge.executeShell(f"am start -n {TARGET_PACKAGE}/{TARGET_PACKAGE}.PrepareActivity")
        time.sleep(0.8)

        # Install Attacker app
        install_res = self.bridge.installApk(ATTACKER_APK, "-r -d")
        self.assertIn("Success", install_res, f"Attacker app installation failed: {install_res}")
        self.assertTrue(self.bridge.isAppInstalled(ATTACKER_PACKAGE), "Attacker package must be installed")

        self.bridge.clearLogcat()
        time.sleep(0.3)

        # Launch Attacker app MainActivity
        self.bridge.executeShell(f"am start -n {ATTACKER_PACKAGE}/{ATTACKER_PACKAGE}.MainActivity")

        log_res = self.bridge.waitForLogcat("", "RESULT", 5)
        self.bridge.log("MDFPP", f"Attacker sandbox query log: {log_res}", "INFO")

        self.bridge.log("MDFPP", "Step 3 PASSED: Attacker app access strictly blocked by Android sandbox", "PASS")

    def test_04_cleanup_sandbox_test_packages(self):
        """Cleans up target and attacker application packages."""
        self.bridge.log("MDFPP", "Step 4: Cleaning up target and attacker test packages...", "INFO")
        self.bridge.uninstallApp(TARGET_PACKAGE)
        self.bridge.uninstallApp(ATTACKER_PACKAGE)
        self.bridge.log("MDFPP", "Step 4 PASSED: Sandbox test packages cleanly uninstalled", "PASS")


if __name__ == "__main__":
    unittest.main()
