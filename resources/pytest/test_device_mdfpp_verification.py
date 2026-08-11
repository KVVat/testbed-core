"""
MDFPP Device Security & Integrity Verification Test Suite
Common Criteria (CC) MDFPP v3.3 Compliance Tests for Connected Target Device
"""

import unittest
import time
import re
import os

# Metadata for Testbed Core Test Explorer & CC Evaluation Report
CATEGORY = "Python Test"
TITLE = "Connected Target Device Security & Integrity Verification"
DESCRIPTION = "Verifies OS release version, security patch level, File-Based Encryption (FBE), SELinux status, and system clock synchronization on the connected Android target device."


class TestDeviceMdfppVerification(unittest.TestCase):

    def setUp(self):
        # Access host bridge injected by Testbed Core
        self.bridge = globals().get("bridge", None)

    def _require_device(self):
        """Helper to assert device connectivity or skip if no device is connected."""
        if not self.bridge:
            self.skipTest("Host bridge is not available in standalone execution.")
        serial = self.bridge.getDeviceSerial()
        if not serial:
            self.skipTest("No target Android device connected to Testbed Core.")
        return serial

    def test_01_device_connection_and_boot(self):
        """MDFPP Pre-condition: Verifies device connectivity and sys.boot_completed state."""
        serial = self._require_device()
        self.assertTrue(len(serial) > 0, "Device serial must not be empty")

        boot_completed = self.bridge.getProp("sys.boot_completed").strip()
        self.assertEqual(boot_completed, "1", f"Target device {serial} must have completed boot (sys.boot_completed=1)")
        
        model = self.bridge.getDeviceModel()
        self.bridge.log("MDFPP", f"Target Device: {serial} (Model: {model}) is connected and booted", "INFO")

    def test_02_os_integrity_and_security_patch(self):
        """FPT_TUD_EXT.1: Verifies OS release version and valid YYYY-MM-DD security patch level."""
        self._require_device()
        
        os_version = self.bridge.getProp("ro.build.version.release").strip()
        self.assertTrue(len(os_version) > 0, "ro.build.version.release must be populated")
        
        # Security patch level format check (YYYY-MM-DD)
        patch_level = self.bridge.getProp("ro.build.version.security_patch").strip()
        self.assertTrue(
            bool(re.match(r"^\d{4}-\d{2}-\d{2}$", patch_level)),
            f"Security patch level '{patch_level}' must match YYYY-MM-DD format"
        )
        self.bridge.log("MDFPP", f"FPT_TUD_EXT.1: OS Version={os_version}, Security Patch={patch_level}", "PASS")

    def test_03_file_based_encryption_state(self):
        """FCS_CKM_EXT.4 / Storage Encryption: Verifies File-Based Encryption (FBE) is active."""
        self._require_device()
        
        crypto_type = self.bridge.getProp("ro.crypto.type").strip()
        crypto_state = self.bridge.getProp("ro.crypto.state").strip()
        
        # Modern Android devices must use File-Based Encryption
        self.assertEqual(crypto_type, "file", f"ro.crypto.type should be 'file' (FBE), found '{crypto_type}'")
        self.assertEqual(crypto_state, "encrypted", f"ro.crypto.state must be 'encrypted', found '{crypto_state}'")
        self.bridge.log("MDFPP", f"FCS_CKM_EXT.4: Storage Encryption is active (type={crypto_type}, state={crypto_state})", "PASS")

    def test_04_selinux_enforcement_status(self):
        """FDP_IFC_EXT.1: Verifies Mandatory Access Control (SELinux) subsystem status."""
        self._require_device()
        
        selinux_status = self.bridge.executeShell("getenforce").strip()
        self.assertIn(
            selinux_status,
            ["Enforcing", "Permissive"],
            f"SELinux status must be Enforcing (or Permissive in test builds), got '{selinux_status}'"
        )
        self.bridge.log("MDFPP", f"FDP_IFC_EXT.1: SELinux status is '{selinux_status}'", "PASS")

    def test_05_system_clock_synchronization(self):
        """FPT_STM.1: Verifies reliable time stamps between host and target Android device."""
        self._require_device()
        
        target_timestamp_str = self.bridge.executeShell("date +%s").strip()
        try:
            target_epoch = int(target_timestamp_str)
            host_epoch = int(time.time())
            diff_seconds = abs(target_epoch - host_epoch)
            
            # Allow up to 300 seconds (5 min) drift
            self.assertLessEqual(
                diff_seconds, 300,
                f"Target time ({target_epoch}) and Host time ({host_epoch}) drift ({diff_seconds}s) exceeds 300s threshold"
            )
            self.bridge.log("MDFPP", f"FPT_STM.1: Clock sync verified (Time drift: {diff_seconds}s)", "PASS")
        except ValueError:
            self.fail(f"Failed to parse target device timestamp: '{target_timestamp_str}'")

    def test_06_platform_framework_package_manager(self):
        """Security Sandbox: Verifies core platform package manager and framework presence."""
        self._require_device()
        
        pm_output = self.bridge.executeShell("pm path android").strip()
        self.assertTrue(
            "package:" in pm_output and "framework-res.apk" in pm_output,
            f"Framework package path must resolve correctly, got: '{pm_output}'"
        )
        self.bridge.log("MDFPP", f"Platform framework verified: {pm_output}", "PASS")


if __name__ == "__main__":
    unittest.main()
