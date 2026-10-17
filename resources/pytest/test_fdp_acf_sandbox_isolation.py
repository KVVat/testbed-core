"""
MDFPP Security Functional Requirement Test: FDP_ACF_EXT.1.2
Application Sandbox & Private Data Isolation Verification

This test verifies that the Android platform strictly enforces user and UID-based
application sandboxing, ensuring that an attacker application cannot access private
storage files belonging to another target application.

Both test applications print one result line under the log tag
"FDP_ACC_1_TEST:RESULT" in the form "<preference>/<private file>/<media>/<room db>".
Preference, private file and Room DB are private application data protected by
FDP_ACF_EXT.1.2. Media lives in shared storage (MediaStore), which the requirement
explicitly distinguishes from private data, so it is only logged.

Each step installs and prepares what it needs, so a single step can be run on its
own from the Test Explorer without producing a false PASS.
"""

import re
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

# e.g. "I FDP_ACC_1_TEST:RESULT: true/true/false/true"
RESULT_PATTERN = r"FDP_ACC_1_TEST:RESULT\W+(true|false)/(true|false)/(true|false)/(true|false)"
RESULT_FIELDS = ("preference", "private_file", "media", "room_db")
PRIVATE_FIELDS = ("preference", "private_file", "room_db")
RESULT_TIMEOUT_SEC = 15


class TestFdpAcfSandboxIsolation(unittest.TestCase):

    def setUp(self):
        self.bridge = globals().get("bridge", None)
        if not self.bridge or not self.bridge.isDeviceConnected():
            self.skipTest("Connected Android target device is required for Sandbox Isolation test.")

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------
    def _install_fresh(self, apk, package):
        self.bridge.uninstallApp(package)
        time.sleep(0.3)
        install_res = self.bridge.installApk(apk, "-r -d")
        self.assertIn("Success", install_res, f"{apk} installation failed: {install_res}")
        self.assertTrue(self.bridge.isAppInstalled(package), f"{package} must be installed")

    def _prepare_target_data(self):
        """Writes preference, private file, media and Room DB entries in the target app."""
        out = self.bridge.executeShell(f"am start -W -S -n {TARGET_PACKAGE}/{TARGET_PACKAGE}.PrepareActivity")
        self.bridge.log("MDFPP", f"PrepareActivity launch: {out}", "INFO")
        # Preference and private file are written synchronously; the Room DB write is asynchronous.
        time.sleep(2.0)

    def _run_and_read_result(self, package, label):
        """Starts MainActivity of the given app and returns its parsed access result."""
        self.bridge.clearLogcat()
        time.sleep(0.3)
        out = self.bridge.executeShell(f"am start -W -S -n {package}/{package}.MainActivity")
        self.bridge.log("MDFPP", f"{label} launch: {out}", "INFO")

        line = self.bridge.waitForLogcat("", RESULT_PATTERN, RESULT_TIMEOUT_SEC)
        self.assertIsNotNone(
            line,
            f"{label}: no RESULT line within {RESULT_TIMEOUT_SEC}s, so access could not be evaluated"
        )
        match = re.search(r"(true|false)/(true|false)/(true|false)/(true|false)", str(line), re.IGNORECASE)
        self.assertIsNotNone(match, f"{label}: unparsable RESULT line: {line}")

        result = dict(zip(RESULT_FIELDS, (value.lower() == "true" for value in match.groups())))
        self.bridge.log("MDFPP", f"{label} access result: {result} (raw: {line})", "INFO")
        return result

    def _detail_logs(self):
        """Per-check detail lines of both apps, attached to failure messages."""
        logs = str(self.bridge.getLogcat("", 300))
        return "\n".join(line for line in logs.splitlines() if "FDP_ACC_1_TEST" in line)

    def _prepare_and_verify_target(self):
        """Prepares target data and confirms the owner can read it (positive control)."""
        self._install_fresh(TARGET_APK, TARGET_PACKAGE)
        self._prepare_target_data()
        own = self._run_and_read_result(TARGET_PACKAGE, "Target")
        self.assertTrue(own["preference"], f"Positive control failed: target cannot read its own preference.\n{self._detail_logs()}")
        self.assertTrue(own["private_file"], f"Positive control failed: target cannot read its own private file.\n{self._detail_logs()}")
        if not own["room_db"]:
            self.bridge.log("MDFPP", "Target Room DB not confirmed; the DB isolation check is weaker in this run.", "WARN")
        return own

    # ------------------------------------------------------------------
    # Tests
    # ------------------------------------------------------------------
    def test_01_install_target_and_prepare_data(self):
        """Installs the target application and initializes private data files."""
        self.bridge.log("MDFPP", "Step 1: Installing target app and writing private data...", "INFO")
        self._prepare_and_verify_target()
        self.bridge.log("MDFPP", "Step 1 PASSED: Target private data initialized and verified", "PASS")

    def test_02_verify_uninstall_data_destruction(self):
        """Verifies that uninstalling the target application cleanly removes its private data."""
        self.bridge.log("MDFPP", "Step 2: Uninstalling and reinstalling target app to verify data destruction...", "INFO")
        self._prepare_and_verify_target()

        uninstall_res = self.bridge.uninstallApp(TARGET_PACKAGE)
        self.assertIn("Success", uninstall_res, f"Target app uninstall failed: {uninstall_res}")
        self.assertFalse(self.bridge.isAppInstalled(TARGET_PACKAGE), "Target package must be removed")

        reinstall_res = self.bridge.installApk(TARGET_APK, "-r -d")
        self.assertIn("Success", reinstall_res, f"Target app reinstallation failed: {reinstall_res}")

        # Launch MainActivity directly without PrepareActivity
        after = self._run_and_read_result(TARGET_PACKAGE, "Target (reinstalled)")
        self.assertFalse(after["preference"], f"Preference survived uninstall.\n{self._detail_logs()}")
        self.assertFalse(after["private_file"], f"Private file survived uninstall.\n{self._detail_logs()}")
        if after["room_db"]:
            self.bridge.log("MDFPP", "Room DB reported present after reinstall (residual data or result race); see detail logs.", "WARN")

        self.bridge.log("MDFPP", "Step 2 PASSED: Previous private data removed upon uninstall", "PASS")

    def test_03_verify_attacker_sandbox_isolation(self):
        """Verifies that an attacker application cannot access private files of the target application."""
        self.bridge.log("MDFPP", "Step 3: Preparing fresh target data and launching attacker app...", "INFO")
        self._prepare_and_verify_target()

        self._install_fresh(ATTACKER_APK, ATTACKER_PACKAGE)
        attacker = self._run_and_read_result(ATTACKER_PACKAGE, "Attacker")
        details = self._detail_logs()

        for field in PRIVATE_FIELDS:
            self.assertFalse(
                attacker[field],
                f"FDP_ACF_EXT.1.2 violated: attacker could access target {field}.\n{details}"
            )
        self.bridge.log(
            "MDFPP",
            f"Shared storage (media) visible to attacker: {attacker['media']} (outside FDP_ACF_EXT.1.2 private data)",
            "INFO"
        )

        self.bridge.log("MDFPP", "Step 3 PASSED: Attacker app access strictly blocked by Android sandbox", "PASS")

    def test_04_cleanup_sandbox_test_packages(self):
        """Cleans up target and attacker application packages."""
        self.bridge.log("MDFPP", "Step 4: Cleaning up target and attacker test packages...", "INFO")
        self.bridge.uninstallApp(TARGET_PACKAGE)
        self.bridge.uninstallApp(ATTACKER_PACKAGE)
        self.bridge.log("MDFPP", "Step 4 PASSED: Sandbox test packages cleanly uninstalled", "PASS")


if __name__ == "__main__":
    unittest.main()
