"""
FDP_DAR_EXT.1 Direct Boot & Protected Data Storage Encryption Verification
Common Criteria (CC) MDFPP v3.3 Compliance Test for Target Android Device

Verifies that the target Android platform strictly enforces File-Based Encryption (FBE):
- Device Encrypted Storage (DES) is accessible in Before-First-Unlock (BFU) state upon reboot.
- Credential Encrypted Storage (CES) remains protected and inaccessible until first user unlock.

Evidence used after the reboot (BFU):
- The direct-boot-aware BootReceiver of the test app logs "des=<result>,ces=<result>" on
  LOCKED_BOOT_COMPLETED. DES must be readable ("des=Success").
- CES inaccessibility is proven by the platform's IllegalStateException
  ("SharedPreferences in credential encrypted storage are not available until after user is
  unlocked") printed by the same receiver process. "ces=Failed" alone is not sufficient,
  because the app keeps its value in DES only, so CES reads "Failed" even when unlocked.
- `dumpsys user` must report RUNNING_LOCKED, which requires a lock screen credential.
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
CE_LOCKED_MESSAGE = "credential encrypted storage are not available"
BFU_RESULT_TIMEOUT_SEC = 60

# threadtime format: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
THREADTIME_PID = re.compile(r"^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s")


def _pid_of(line):
    match = THREADTIME_PID.match(line)
    return match.group(1) if match else None


class TestFdpDarDirectBootEncryption(unittest.TestCase):

    def setUp(self):
        self.bridge = globals().get("bridge", None)
        if not self.bridge or not self.bridge.isDeviceConnected():
            self.skipTest("Connected Android target device is required for Direct Boot / DAR tests.")

    def _bfu_receiver_evidence(self):
        """Returns (result_line, ce_locked_seen, raw_log) for the LOCKED_BOOT_COMPLETED run.

        The whole log buffer is dumped with a tag filter, because the receiver runs early in
        boot and its lines are quickly pushed out of a short `logcat -t` tail.
        """
        raw = str(self.bridge.executeShell(f"logcat -d -s {LOG_TAG} System.err"))
        lines = raw.splitlines()
        marker = next((i for i, line in enumerate(lines) if "LOCKED_BOOT_COMPLETED" in line), None)
        if marker is None:
            return None, False, raw

        receiver_pid = _pid_of(lines[marker])
        ce_locked = False
        for line in lines[marker + 1:]:
            same_process = receiver_pid is None or _pid_of(line) == receiver_pid
            if CE_LOCKED_MESSAGE in line and same_process:
                ce_locked = True
            if "des=" in line and LOG_TAG in line:
                return line, ce_locked, raw
        return None, ce_locked, raw

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

        # Launch the application main activity (force-stopped first so onStart always runs)
        launch_out = self.bridge.executeShell(
            f"am start -W -S -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {TEST_PACKAGE}"
        )
        self.bridge.log("MDFPP", f"App launch trigger result: {launch_out}", "INFO")

        # Wait for initialization confirmation log
        boot_log = self.bridge.waitForLogcat(LOG_TAG, "Booted", 15)
        self.assertIsNotNone(boot_log, f"Application initialization log ('Booted') was not observed under tag {LOG_TAG}")
        
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

        user_state = self.bridge.executeShell("dumpsys user")
        self.assertIn(
            "RUNNING_LOCKED", user_state,
            "Device must stay in BFU (RUNNING_LOCKED) after reboot; set a lock screen PIN/password before running this test"
        )
        self.bridge.log("MDFPP", "Step 3 PASSED: Device successfully rebooted into BFU state", "PASS")

    def test_04_verify_directboot_bfu_storage_isolation(self):
        """Verifies that DES is accessible in BFU state while CES remains protected."""
        self.bridge.log("MDFPP", "Step 4: Checking storage access logs from LOCKED_BOOT_COMPLETED broadcast...", "INFO")

        # The receiver may run a little after sys.boot_completed; poll for its result line.
        deadline = time.time() + BFU_RESULT_TIMEOUT_SEC
        result_line, ce_locked, raw = None, False, ""
        while time.time() < deadline:
            result_line, ce_locked, raw = self._bfu_receiver_evidence()
            if result_line:
                break
            time.sleep(3)

        tail = "\n".join(raw.splitlines()[-40:])
        self.assertIsNotNone(
            result_line,
            f"INCONCLUSIVE: no LOCKED_BOOT_COMPLETED result from the direct-boot-aware receiver within "
            f"{BFU_RESULT_TIMEOUT_SEC}s (was the app launched before the reboot?).\n{tail}"
        )
        self.bridge.log("MDFPP", f"Observed DirectBoot log: {result_line}", "INFO")

        # Verify DES access was successful
        self.assertIn("des=Success", result_line, "Device Encrypted Storage (DES) MUST be accessible in BFU state")
        # Verify CES access failed / locked before first unlock
        self.assertIn("ces=Failed", result_line, "Credential Encrypted Storage (CES) MUST NOT be accessible before first unlock")
        self.assertTrue(
            ce_locked,
            "The platform did not report CES as locked in the receiver process "
            f"(expected IllegalStateException '{CE_LOCKED_MESSAGE}').\n{tail}"
        )

        user_state = self.bridge.executeShell("dumpsys user")
        self.assertIn("RUNNING_LOCKED", user_state, "Device must still be in BFU (RUNNING_LOCKED) during the check")

        # Supplementary evidence only (not a pass criterion)
        fbe_prop = self.bridge.getProp("ro.crypto.type")
        fbe_state = self.bridge.getProp("ro.crypto.state")
        self.bridge.log("MDFPP", f"FBE properties: ro.crypto.type={fbe_prop}, ro.crypto.state={fbe_state}", "INFO")

        self.bridge.log("MDFPP", "Step 4 PASSED: Direct Boot FBE storage isolation strictly enforced", "PASS")

    def test_05_cleanup_test_package(self):
        """Cleans up the directboot test package from the target device."""
        self.bridge.log("MDFPP", "Step 5: Cleaning up directboot test package...", "INFO")
        uninstall_res = self.bridge.uninstallApp(TEST_PACKAGE)
        self.assertFalse(self.bridge.isAppInstalled(TEST_PACKAGE), f"Package {TEST_PACKAGE} should be uninstalled")
        self.bridge.log("MDFPP", "Step 5 PASSED: Cleanup completed successfully", "PASS")


if __name__ == "__main__":
    unittest.main()
