"""
Sample Idiomatic MDFPP Python Test Suite
Demonstrates the high-level 'testbed' helper package syntax for testbed-core.
"""

import unittest
from testbed import device, app, logcat, log

SHORT_NAME = "test_sample_idiomatic_mdfpp"
TITLE = "Idiomatic MDFPP Test Structure Example"
DESCRIPTION = "Demonstrates clean, Pythonic Common Criteria test execution using the testbed helper library."
CATEGORY = "Python Test"


class TestSampleIdiomaticMdfpp(unittest.TestCase):

    def setUp(self):
        if not device.is_connected():
            self.skipTest("Physical Android device required for this test.")

    def test_01_verify_device_security_props(self):
        """Verifies platform encryption and security build properties."""
        log("MDFPP", "Checking File-Based Encryption (FBE) configuration...", "INFO")
        
        crypto_type = device.get_prop("ro.crypto.type")
        crypto_state = device.get_prop("ro.crypto.state")
        
        log("MDFPP", f"ro.crypto.type: {crypto_type}, ro.crypto.state: {crypto_state}", "INFO")
        self.assertEqual("file", crypto_type, "FBE must be file-based")
        self.assertEqual("encrypted", crypto_state, "Storage must be encrypted")
        
        log("MDFPP", "Step 1 PASSED: Device storage encryption validated", "PASS")

    def test_02_selinux_and_user_unlock_state(self):
        """Verifies SELinux is enforcing and device is in AFU unlocked state."""
        log("MDFPP", "Checking SELinux enforcement and user state...", "INFO")
        
        selinux_out = device.shell("getenforce").strip()
        self.assertEqual("Enforcing", selinux_out, "SELinux must be Enforcing")
        
        # Ensure device is unlocked
        unlocked = device.unlock("0000")
        self.assertTrue(unlocked, "Device must be in AFU unlocked state")
        
        log("MDFPP", "Step 2 PASSED: SELinux enforcing and device unlocked", "PASS")


if __name__ == "__main__":
    unittest.main()
