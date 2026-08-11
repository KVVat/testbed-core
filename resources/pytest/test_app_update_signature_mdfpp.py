"""
FPT_TUD_EXT.1 Application Update Signature Verification Test Suite
Common Criteria (CC) MDFPP v3.3 Compliance Test for Target Android Device
"""

import unittest
import time
import re

CATEGORY = "Python Test"
TITLE = "Application Update Signature & Integrity Verification"
DESCRIPTION = "Verifies that the Android platform strictly enforces digital signature consistency during application updates, rejecting mismatched and unsigned APKs while accepting valid updates."

PACKAGE_NAME = "com.example.appupdate"


class TestAppUpdateSignatureMdfpp(unittest.TestCase):

    def setUp(self):
        self.bridge = globals().get("bridge", None)
        if not self.bridge or not self.bridge.isDeviceConnected():
            self.skipTest("Connected Android target device is required for APK update tests.")

    def tearDown(self):
        # Ensure clean state after tests
        pass

    def test_01_install_initial_version_v1(self):
        """Installs the initial application package (v1) signed with standard test key."""
        self.bridge.log("MDFPP", "Step 1: Preparing clean environment and installing v1 APK...", "INFO")
        
        # Cleanup any previous instance
        self.bridge.uninstallApp(PACKAGE_NAME)
        time.sleep(0.5)

        install_res = self.bridge.installApk("appupdate-v1.apk", "-r -d")
        self.assertIn("Success", install_res, f"Initial install of appupdate-v1.apk failed: {install_res}")
        self.assertTrue(self.bridge.isAppInstalled(PACKAGE_NAME), f"Package {PACKAGE_NAME} should be installed")
        
        # Verify version code is 1
        dumpsys_out = self.bridge.executeShell(f"dumpsys package {PACKAGE_NAME}")
        self.assertIn("versionCode=1", dumpsys_out, "Installed package should be version code 1")
        self.bridge.log("MDFPP", "Step 1 PASSED: v1 successfully installed with valid signature", "PASS")

    def test_02_reject_mismatched_signature_update(self):
        """Verifies that an update package with a conflicting/mismatched signature is rejected."""
        self.bridge.log("MDFPP", "Step 2: Attempting update with mismatched signature APK...", "INFO")
        
        # Must fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE or signature mismatch
        update_res = self.bridge.installApk("appupdate-mismatched.apk", "-r")
        self.assertNotIn("Success", update_res, "Update with mismatched signature MUST NOT succeed")
        
        is_rejected = any(err in update_res for err in [
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            "signatures do not match",
            "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
            "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES"
        ])
        self.assertTrue(is_rejected, f"Expected signature incompatibility rejection error, got: '{update_res}'")
        
        # Verify original app is still intact and runnable
        self.assertTrue(self.bridge.isAppInstalled(PACKAGE_NAME), "Original package must remain installed")
        dumpsys_out = self.bridge.executeShell(f"dumpsys package {PACKAGE_NAME}")
        self.assertIn("versionCode=1", dumpsys_out, "Package version must remain 1 after rejected update")
        self.bridge.log("MDFPP", "Step 2 PASSED: Mismatched signature update properly blocked by OS", "PASS")

    def test_03_reject_unsigned_update(self):
        """Verifies that an unsigned or malformed APK update is rejected by the package manager."""
        self.bridge.log("MDFPP", "Step 3: Attempting update with unsigned APK...", "INFO")
        
        update_res = self.bridge.installApk("appupdate-unsigned.apk", "-r")
        self.assertNotIn("Success", update_res, "Update with unsigned APK MUST NOT succeed")
        
        is_rejected = any(err in update_res for err in [
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
            "INSTALL_FAILED_INVALID_APK",
            "Failed to parse",
            "INSTALL_FAILED"
        ])
        self.assertTrue(is_rejected, f"Expected signature parsing error for unsigned APK, got: '{update_res}'")
        self.bridge.log("MDFPP", "Step 3 PASSED: Unsigned APK update properly blocked", "PASS")

    def test_04_accept_valid_signature_update_v2(self):
        """Verifies that an update package signed with the matching key succeeds and upgrades the app."""
        self.bridge.log("MDFPP", "Step 4: Applying valid update (v2) signed with matching key...", "INFO")
        
        update_res = self.bridge.installApk("appupdate-v2.apk", "-r -d")
        self.assertIn("Success", update_res, f"Update to appupdate-v2.apk should succeed, got: {update_res}")
        
        dumpsys_out = self.bridge.executeShell(f"dumpsys package {PACKAGE_NAME}")
        self.assertIn("versionCode=2", dumpsys_out, "Package version code must be upgraded to 2")
        self.bridge.log("MDFPP", "Step 4 PASSED: Valid signature update successfully applied to v2", "PASS")

    def test_05_cleanup_installed_test_package(self):
        """Cleans up the test application from the target device."""
        self.bridge.log("MDFPP", "Step 5: Cleaning up test package...", "INFO")
        uninstall_res = self.bridge.uninstallApp(PACKAGE_NAME)
        self.assertFalse(self.bridge.isAppInstalled(PACKAGE_NAME), f"Package {PACKAGE_NAME} should be uninstalled")
        self.bridge.log("MDFPP", "Step 5 PASSED: Target package cleanly uninstalled", "PASS")


if __name__ == "__main__":
    unittest.main()
