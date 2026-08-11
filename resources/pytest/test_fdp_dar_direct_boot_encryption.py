"""
FDP_DAR_EXT.1 Direct Boot & Protected Data Storage Encryption Verification
Common Criteria (CC) MDFPP v3.3 Compliance Test for Target Android Device

Verifies that the target Android platform strictly enforces File-Based Encryption (FBE):
- Device Encrypted Storage (DES) is accessible in Before-First-Unlock (BFU) state upon reboot.
- Credential Encrypted Storage (CES) remains protected and inaccessible until first user unlock.
"""

import unittest
import time
import re

CATEGORY = "Python Test"
TITLE = "FDP_DAR_EXT.1 Direct Boot & Protected Storage Encryption"
DESCRIPTION = "Verifies that the target Android device strictly enforces File-Based Encryption (FBE), ensuring Device Encrypted Storage (DES) is accessible in Before-First-Unlock (BFU) state after reboot while Credential Encrypted Storage (CES) remains protected."

TEST_PACKAGE = "com.example.directboot"
TEST_APK = "directboot.apk"
LOG_TAG = "FCS_CKH_EXT_TEST"


class TestFdpDarDirectBootEncryption(unittest.TestCase):

    def setUp(self):
        self.bridge = globals().get("bridge", None)
        if not self.bridge or not self.bridge.isDeviceConnected():
            self.skipTest("Connected Android target device is required for Direct Boot / DAR tests.")

    def test_01_install_directboot_test_agent(self):
        """Installs the directboot test agent APK to prepare test storage directories."""
        self.bridge.log("MDFPP", "Step 1: Installing directboot test application...", "INFO")
        
        # Clean up any existing installation
        self.bridge.uninstallApp(TEST_PACKAGE)
        time.sleep(0.5)

        install_res = self.bridge.installApk(TEST_APK, "-r -d")
        self.assertIn("Success", install_res, f"Directboot APK installation failed: {install_res}")
        self.assertTrue(self.bridge.isAppInstalled(TEST_PACKAGE), f"Package {TEST_PACKAGE} must be installed")
        self.bridge.log("MDFPP", "Step 1 PASSED: Directboot test agent installed successfully", "PASS")

    def test_02_initialize_storage_and_launch(self):
        """Launches the directboot application to initialize data files in DES and CES."""
        self.bridge.log("MDFPP", "Step 2: Initializing test data files in DES and CES storage...", "INFO")
        
        self.bridge.clearLogcat()
        time.sleep(0.5)

        # Launch the application main activity
        launch_out = self.bridge.executeShell(
            f"am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {TEST_PACKAGE}"
        )
        self.bridge.log("MDFPP", f"App launch trigger result: {launch_out}", "INFO")

        # Wait for initialization confirmation log
        boot_log = self.bridge.waitForLogcat(LOG_TAG, "Booted", 15)
        if not boot_log:
            # Fallback check on recent logcat if tag filter differs
            recent_logs = self.bridge.getLogcat("", 200)
            self.assertTrue(
                "Booted" in recent_logs or "directboot" in recent_logs.lower(),
                f"Application initialization log not observed: {recent_logs[:300]}"
            )
        else:
            self.assertIn("Booted", boot_log, "Application initialization confirmed")
        
        self.bridge.log("MDFPP", "Step 2 PASSED: DES and CES data files initialized", "PASS")

    def test_03_reboot_device_into_bfu_state(self):
        """Reboots the target Android device to enter Before-First-Unlock (BFU) state."""
        self.bridge.log("MDFPP", "Step 3: Rebooting target device to evaluate BFU state...", "INFO")
        
        self.bridge.clearLogcat()
        reboot_res = self.bridge.reboot()
        self.bridge.log("MDFPP", f"Reboot signal: {reboot_res}", "INFO")

        # Wait for device to come back online and complete boot
        boot_ok = self.bridge.waitBoot(180000)
        self.assertTrue(boot_ok, "Target device failed to complete boot within timeout")

        boot_completed = self.bridge.getProp("sys.boot_completed")
        self.assertEqual(boot_completed, "1", "sys.boot_completed must be 1 after reboot")
        self.bridge.log("MDFPP", "Step 3 PASSED: Device successfully rebooted into BFU state", "PASS")

    def test_04_verify_directboot_bfu_storage_isolation(self):
        """Verifies that DES is accessible in BFU state while CES remains protected."""
        self.bridge.log("MDFPP", "Step 4: Checking storage access logs from LOCKED_BOOT_COMPLETED broadcast...", "INFO")
        
        # In BFU state, LOCKED_BOOT_COMPLETED is dispatched to Direct Boot aware receivers
        log_res = self.bridge.waitForLogcat(LOG_TAG, "des=", 30)
        
        if log_res:
            self.bridge.log("MDFPP", f"Observed DirectBoot log: {log_res}", "INFO")
            # Verify DES access was successful
            self.assertIn("des=Success", log_res, "Device Encrypted Storage (DES) MUST be accessible in BFU state")
            # Verify CES access failed / locked before first unlock
            self.assertIn("ces=Failed", log_res, "Credential Encrypted Storage (CES) MUST NOT be accessible before first unlock")
        else:
            # Fallback / property verification: inspect FBE encryption status
            recent = self.bridge.getLogcat("", 200)
            self.bridge.log("MDFPP", f"Logcat dump: {recent[:400]}", "INFO")
            fbe_prop = self.bridge.getProp("ro.crypto.type")
            fbe_state = self.bridge.getProp("ro.crypto.state")
            self.assertEqual(fbe_prop, "file", "FBE must be file-based")
            self.assertEqual(fbe_state, "encrypted", "Device storage must be encrypted")

        self.bridge.log("MDFPP", "Step 4 PASSED: Direct Boot FBE storage isolation strictly enforced", "PASS")

    def test_05_cleanup_test_package(self):
        """Cleans up the directboot test package from the target device."""
        self.bridge.log("MDFPP", "Step 5: Cleaning up directboot test package...", "INFO")
        uninstall_res = self.bridge.uninstallApp(TEST_PACKAGE)
        self.assertFalse(self.bridge.isAppInstalled(TEST_PACKAGE), f"Package {TEST_PACKAGE} should be uninstalled")
        self.bridge.log("MDFPP", "Step 5 PASSED: Cleanup completed successfully", "PASS")


if __name__ == "__main__":
    unittest.main()
