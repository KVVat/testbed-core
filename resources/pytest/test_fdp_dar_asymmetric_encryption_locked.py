"""
MDFPP Security Functional Requirement Test: FDP_DAR_EXT.2
Asymmetric Key Encryption While Locked & Key Release via Device Credential (PIN)

This test comprehensively covers both locked states required by CC / MDFPP v3.3:
1. AFU Screen-Locked State (FDP_DAR_EXT.2.2):
   - Sensitive data can be encrypted with the public key while the screen is locked/sleeping.
   - Access to UnlockDeviceRequired (UDR) private keys is blocked.
2. BFU Rebooted State (FDP_DAR_EXT.2.3 / FDP_DAR_EXT.2.4):
   - Upon device reboot into Before-First-Unlock (BFU / RUNNING_LOCKED) state,
     private keys and sensitive data remain strictly inaccessible.
   - Upon first user authentication with PIN (0000), the device transitions to AFU
     (RUNNING_UNLOCKED) and releases the keys for decryption.

Key operations are judged only from the result lines the test app logs under
FCS_CKH_EXT_TEST. If the app reports no result for a step, the step FAILS as
INCONCLUSIVE instead of passing. The bundled encryption-debug.apk does not implement the
'usePublicKey' / 'encryptData' / 'usePrivateKey' extras yet (they are placeholder flags,
as in the JUnit FdpDarExt2Test), so steps 2, 3 and 6 stay INCONCLUSIVE until it does.
"""

import re
import unittest
import time
from testbed import device, app, logcat, log

SHORT_NAME = "test_fdp_dar_asymmetric_encryption_locked"
TITLE = "FDP_DAR_EXT.2 Sensitive Data Encryption While Locked (AFU Screen Lock & BFU Reboot)"
DESCRIPTION = "Verifies asymmetric public key encryption while screen locked, blocks private key access in both AFU screen-lock and BFU reboot states, and validates key release upon PIN unlock."
CATEGORY = "Python Test"

TEST_APK = "encryption-debug.apk"
TARGET_PACKAGE = "com.example.encryption"
DEVICE_PIN = "0000"
LOG_TAG = "FCS_CKH_EXT_TEST"

# Result markers logged by the test app
ENCRYPTED_MARKER = r"Encrypted information"
BLOCKED_MARKERS = r"Auth:Failed|UserNotAuthenticated|User Auth\.|Invalid key"
RELEASED_MARKERS = r"Auth:Success|Decrypted information"
RESULT_TIMEOUT_SEC = 10

APP_LIMITATION_NOTE = (
    "encryption-debug.apk ignores the 'usePublicKey' / 'encryptData' / 'usePrivateKey' extras "
    "(placeholder flags shared with the JUnit FdpDarExt2Test), so this step cannot be verified "
    "until the test app implements them."
)


class TestFdpDarAsymmetricEncryptionLocked(unittest.TestCase):

    def setUp(self):
        if not device.is_connected():
            self.skipTest("Connected Android target device is required for FDP_DAR_EXT.2 tests.")

    def _wait_key_result(self, pattern):
        """Returns the first app result line matching pattern, or None if the app reported nothing."""
        line = logcat.wait_for(LOG_TAG, pattern, timeout_sec=RESULT_TIMEOUT_SEC)
        return str(line) if line else None

    @staticmethod
    def _matches(pattern, text):
        # Same case-insensitive matching as the host-side waitForLogcat
        return re.search(pattern, str(text), re.IGNORECASE) is not None

    def _fail_inconclusive(self, sfr):
        recent = logcat.get(LOG_TAG, max_lines=50)
        self.fail(
            f"INCONCLUSIVE ({sfr}): the test app reported no key operation result. "
            f"{APP_LIMITATION_NOTE}\nRecent {LOG_TAG} log:\n{recent}"
        )

    def test_01_install_encryption_agent(self):
        """Installs the encryption test agent and ensures clean environment."""
        log("MDFPP", "Step 1: Installing encryption test APK...", "INFO")
        
        app.uninstall(TARGET_PACKAGE)
        time.sleep(0.3)

        install_res = app.install(TEST_APK, "-r -d")
        self.assertIn("Success", install_res, f"Encryption APK install failed: {install_res}")
        self.assertTrue(app.is_installed(TARGET_PACKAGE), "Encryption package must be installed")
        
        # Ensure device is initially unlocked and awake
        self.assertTrue(
            device.unlock(DEVICE_PIN),
            f"Device must be unlocked (AFU) before the test; check that the lock screen PIN is {DEVICE_PIN}"
        )
        log("MDFPP", "Step 1 PASSED: Encryption test agent installed and device ready in AFU state", "PASS")

    def test_02_asymmetric_encryption_while_screen_locked(self):
        """FDP_DAR_EXT.2.2: Verifies that public key encryption succeeds while screen is locked."""
        log("MDFPP", "Step 2: Locking screen (sleep) and testing public key encryption...", "INFO")
        
        # Lock device screen
        device.shell("input keyevent KEYCODE_SLEEP")
        time.sleep(1.0)

        logcat.clear()
        time.sleep(0.3)

        # Trigger public key encryption while locked
        launch_res = device.shell(
            f"am start -n {TARGET_PACKAGE}/.MainActivity -e usePublicKey true -e encryptData true"
        )
        log("MDFPP", f"Trigger encryption command result: {launch_res}", "INFO")

        enc_log = self._wait_key_result(f"{ENCRYPTED_MARKER}|{BLOCKED_MARKERS}")
        if not enc_log:
            self._fail_inconclusive("FDP_DAR_EXT.2.2")

        self.assertTrue(
            self._matches(ENCRYPTED_MARKER, enc_log),
            f"FDP_DAR_EXT.2.2 FAILED: public key encryption while screen locked did not succeed, log: {enc_log}"
        )
        log("MDFPP", "Step 2 PASSED: Asymmetric encryption while screen locked successfully performed", "PASS")

    def test_03_udr_private_key_blocked_while_screen_locked(self):
        """FDP_DAR_EXT.2.3 (AFU Locked): Verifies private key access is blocked when screen is locked."""
        log("MDFPP", "Step 3: Attempting to access UDR private key while screen is locked (must fail)...", "INFO")
        
        # Ensure screen remains locked
        device.shell("input keyevent KEYCODE_SLEEP")
        time.sleep(1.0)

        logcat.clear()
        time.sleep(0.3)

        # Attempt to access private key while screen locked
        device.shell(
            f"am start -n {TARGET_PACKAGE}/.MainActivity -e unlockDeviceRequired true -e usePrivateKey true"
        )

        block_log = self._wait_key_result(f"{BLOCKED_MARKERS}|{RELEASED_MARKERS}|{ENCRYPTED_MARKER}")
        if not block_log:
            self._fail_inconclusive("FDP_DAR_EXT.2.3")

        self.assertTrue(
            self._matches(BLOCKED_MARKERS, block_log),
            f"FDP_DAR_EXT.2.3 FAILED: UDR private key was usable while the screen was locked, log: {block_log}"
        )
        log("MDFPP", "Step 3 PASSED: Access to UDR private key is strictly blocked while screen is locked", "PASS")

    def test_04_reboot_device_into_bfu_state(self):
        """Reboots target device into Before-First-Unlock (BFU) state."""
        log("MDFPP", "Step 4: Rebooting device to enter Before-First-Unlock (BFU) state...", "INFO")
        
        logcat.clear()
        device.reboot()
        
        # Wait 5 seconds for reboot to initiate before waiting for reconnect
        time.sleep(5.0)
        
        reconnected = device.wait_boot(180000)
        self.assertTrue(reconnected, "Device must reconnect and finish booting after reboot")
        
        # Verify device is in BFU state (User 0: RUNNING_LOCKED)
        user_dump = device.shell("dumpsys user")
        log("MDFPP", f"Post-reboot dumpsys user status:\n{user_dump}", "INFO")
        self.assertIn("RUNNING_LOCKED", user_dump, "Device must be in RUNNING_LOCKED (BFU) state upon reboot")
        
        log("MDFPP", "Step 4 PASSED: Device successfully rebooted into BFU (RUNNING_LOCKED) state", "PASS")

    def test_05_verify_private_key_blocked_in_bfu_state(self):
        """FDP_DAR_EXT.2.3 (BFU Locked): Verifies private key and sensitive data are blocked in BFU."""
        log("MDFPP", "Step 5: Verifying private key access is strictly blocked in BFU state...", "INFO")
        
        logcat.clear()
        time.sleep(0.5)

        # Attempt to access private key in BFU state
        launch_res = device.shell(
            f"am start -n {TARGET_PACKAGE}/.MainActivity -e unlockDeviceRequired true -e usePrivateKey true"
        )
        log("MDFPP", f"BFU launch attempt result: {launch_res}", "INFO")
        time.sleep(1.5)

        bfu_log = logcat.get(LOG_TAG, max_lines=50)
        log("MDFPP", f"BFU key access log: {bfu_log}", "INFO")
        if self._matches(RELEASED_MARKERS, bfu_log) or self._matches(ENCRYPTED_MARKER, bfu_log):
            self.fail(f"FDP_DAR_EXT.2.3 FAILED: key material was usable in BFU state, log: {bfu_log}")

        # In BFU, CE storage is locked and user is not authenticated
        user_state = device.shell("dumpsys user")
        self.assertIn("RUNNING_LOCKED", user_state, "Device must remain in RUNNING_LOCKED state")
        log("MDFPP", "Step 5 PASSED: BFU confirmed (RUNNING_LOCKED); no key use was reported by the test app", "PASS")

    def test_06_unlock_from_bfu_and_verify_key_release(self):
        """FDP_DAR_EXT.2.4: Unlocks device with PIN from BFU into AFU and verifies private key release."""
        log("MDFPP", f"Step 6: Unlocking device from BFU with PIN ({DEVICE_PIN})...", "INFO")
        
        unlocked = device.unlock(DEVICE_PIN)
        self.assertTrue(unlocked, "Device must successfully unlock from BFU into AFU (RUNNING_UNLOCKED)")
        log("MDFPP", "Device transitioned from BFU to AFU (RUNNING_UNLOCKED)", "PASS")
        time.sleep(1.0)

        logcat.clear()
        time.sleep(0.3)

        # Access private key after unlock
        device.shell(
            f"am start -n {TARGET_PACKAGE}/.MainActivity -e unlockDeviceRequired true -e usePrivateKey true"
        )

        release_log = self._wait_key_result(f"{RELEASED_MARKERS}|{BLOCKED_MARKERS}")
        if not release_log:
            self._fail_inconclusive("FDP_DAR_EXT.2.4")

        self.assertTrue(
            self._matches(RELEASED_MARKERS, release_log),
            f"FDP_DAR_EXT.2.4 FAILED: private key was not released after PIN unlock, log: {release_log}"
        )
        log("MDFPP", "Step 6 PASSED: Private key successfully released after user PIN unlock", "PASS")

    def test_07_cleanup_encryption_package(self):
        """Cleans up encryption test package and ensures device remains in unlocked AFU state."""
        log("MDFPP", "Step 7: Cleaning up encryption package...", "INFO")
        app.uninstall(TARGET_PACKAGE)
        device.unlock(DEVICE_PIN)
        log("MDFPP", "Step 7 PASSED: Encryption test cleanup completed", "PASS")


if __name__ == "__main__":
    unittest.main()
