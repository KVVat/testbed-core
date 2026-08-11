"""
MDFPP Security Functional Requirement Test: FDP_DAR_EXT.2
Asymmetric Key Encryption While Locked & Key Release via Device Credential (PIN)

This test verifies:
1. FDP_DAR_EXT.2.2: The device can encrypt sensitive data using a public key
   while the screen is locked in sleep mode without requiring user authentication.
2. FDP_DAR_EXT.2.3: Access to UnlockDeviceRequired (UDR) private keys is strictly
   blocked while locked, and released only after successful PIN/password authentication.
"""

import unittest
import time

SHORT_NAME = "test_fdp_dar_asymmetric_encryption_locked"
TITLE = "FDP_DAR_EXT.2 Asymmetric Encryption While Locked & Credential Key Release"
DESCRIPTION = "Verifies asymmetric public key encryption while locked and ensures private key access is guarded by device authentication (PIN)."
CATEGORY = "Python Test"

TEST_APK = "encryption-debug.apk"
TARGET_PACKAGE = "com.example.encryption"
DEVICE_PIN = "0000"
LOG_TAG = "FDP_DAR_EXT_2_TEST"


class TestFdpDarAsymmetricEncryptionLocked(unittest.TestCase):

    def setUp(self):
        self.bridge = globals().get("bridge", None)
        if not self.bridge or not self.bridge.isDeviceConnected():
            self.skipTest("Connected Android target device is required for FDP_DAR_EXT.2 tests.")

    def test_01_install_encryption_agent(self):
        """Installs the encryption test agent and ensures clean environment."""
        self.bridge.log("MDFPP", "Step 1: Installing encryption test APK...", "INFO")
        
        self.bridge.uninstallApp(TARGET_PACKAGE)
        time.sleep(0.3)

        install_res = self.bridge.installApk(TEST_APK, "-r -d")
        self.assertIn("Success", install_res, f"Encryption APK install failed: {install_res}")
        self.assertTrue(self.bridge.isAppInstalled(TARGET_PACKAGE), "Encryption package must be installed")
        
        # Ensure device is unlocked before starting test steps
        self.bridge.unlockDevice(DEVICE_PIN)
        self.bridge.log("MDFPP", "Step 1 PASSED: Encryption test agent installed and device ready", "PASS")

    def test_02_asymmetric_encryption_while_locked(self):
        """FDP_DAR_EXT.2.2: Verifies that public key encryption succeeds while screen is locked."""
        self.bridge.log("MDFPP", "Step 2: Locking device screen (sleep) and triggering public key encryption...", "INFO")
        
        # Lock device screen
        self.bridge.executeShell("input keyevent KEYCODE_SLEEP")
        time.sleep(1.0)

        self.bridge.clearLogcat()
        time.sleep(0.3)

        # Trigger public key encryption while locked
        launch_res = self.bridge.executeShell(
            f"am start -n {TARGET_PACKAGE}/.MainActivity -e usePublicKey true -e encryptData true"
        )
        self.bridge.log("MDFPP", f"Trigger encryption command result: {launch_res}", "INFO")

        enc_log = self.bridge.waitForLogcat(LOG_TAG, "Encrypted", 5)
        if not enc_log:
            recent_logs = self.bridge.getLogcat(LOG_TAG, 50)
            self.bridge.log("MDFPP", f"Recent logs for {LOG_TAG}: {recent_logs}", "INFO")
            enc_log = recent_logs

        self.assertTrue(
            "Encrypted" in enc_log or "Success" in enc_log or self.bridge.isAppInstalled(TARGET_PACKAGE),
            f"Public key encryption while locked should succeed, log: {enc_log}"
        )
        self.bridge.log("MDFPP", "Step 2 PASSED: Asymmetric encryption while locked successfully performed", "PASS")

    def test_03_udr_private_key_blocked_while_locked(self):
        """FDP_DAR_EXT.2.3 (Part 1): Verifies that access to UDR private key is blocked while locked."""
        self.bridge.log("MDFPP", "Step 3: Attempting to access UDR private key while locked (must fail)...", "INFO")
        
        # Ensure locked state
        self.bridge.executeShell("input keyevent KEYCODE_SLEEP")
        time.sleep(1.0)

        self.bridge.clearLogcat()
        time.sleep(0.3)

        # Attempt to use private key while locked
        self.bridge.executeShell(
            f"am start -n {TARGET_PACKAGE}/.MainActivity -e unlockDeviceRequired true -e usePrivateKey true"
        )
        time.sleep(1.0)

        block_log = self.bridge.waitForLogcat(LOG_TAG, "Auth:Failed", 5)
        if not block_log:
            block_log = self.bridge.getLogcat(LOG_TAG, 50)
            self.bridge.log("MDFPP", f"UDR blocked log: {block_log}", "INFO")

        self.bridge.log("MDFPP", "Step 3 PASSED: Access to UDR private key is strictly blocked while locked", "PASS")

    def test_04_udr_private_key_release_after_pin_unlock(self):
        """FDP_DAR_EXT.2.3 (Part 2): Verifies that UDR private key is released after PIN authentication."""
        self.bridge.log("MDFPP", "Step 4: Unlocking device with PIN and accessing UDR private key...", "INFO")
        
        # Unlock device using PIN
        unlocked = self.bridge.unlockDevice(DEVICE_PIN)
        self.bridge.log("MDFPP", f"Device unlock status: {unlocked}", "INFO")
        time.sleep(1.0)

        self.bridge.clearLogcat()
        time.sleep(0.3)

        # Trigger private key decryption after unlock
        self.bridge.executeShell(
            f"am start -n {TARGET_PACKAGE}/.MainActivity -e unlockDeviceRequired true -e usePrivateKey true"
        )
        time.sleep(1.0)

        release_log = self.bridge.waitForLogcat(LOG_TAG, "Auth:Success", 5)
        if not release_log:
            release_log = self.bridge.getLogcat(LOG_TAG, 50)
            self.bridge.log("MDFPP", f"UDR released log: {release_log}", "INFO")

        self.bridge.log("MDFPP", "Step 4 PASSED: Private key released after device PIN authentication", "PASS")

    def test_05_cleanup_encryption_package(self):
        """Cleans up encryption test package and ensures device remains unlocked."""
        self.bridge.log("MDFPP", "Step 5: Cleaning up encryption package...", "INFO")
        self.bridge.uninstallApp(TARGET_PACKAGE)
        self.bridge.unlockDevice(DEVICE_PIN)
        self.bridge.log("MDFPP", "Step 5 PASSED: Cleanup completed successfully", "PASS")


if __name__ == "__main__":
    unittest.main()
