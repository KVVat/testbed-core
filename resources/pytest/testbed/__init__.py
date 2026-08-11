"""
Testbed Python Test Automation Helper Library
High-level wrappers over the TestbedHostBridge for Common Criteria / MDFPP test suites.
"""

import sys

def get_bridge():
    """Returns the host TestbedHostBridge polyglot object."""
    # Check globals in main or caller frames
    import __main__
    if hasattr(__main__, 'bridge'):
        return getattr(__main__, 'bridge')
    # Fallback to sys._getframe
    try:
        f = sys._getframe(1)
        while f:
            if 'bridge' in f.f_globals:
                return f.f_globals['bridge']
            f = f.f_back
    except Exception:
        pass
    return None

class _DeviceHelper:
    """Device lifecycle, power, and state controls."""
    
    @property
    def bridge(self):
        return get_bridge()

    def is_connected(self) -> bool:
        b = self.bridge
        return b.isDeviceConnected() if b else False

    def get_serial(self) -> str:
        b = self.bridge
        return b.getDeviceSerial() if b else ""

    def shell(self, command: str) -> str:
        b = self.bridge
        return b.executeShell(command) if b else ""

    def get_prop(self, prop_name: str) -> str:
        b = self.bridge
        return b.getProp(prop_name) if b else ""

    def reboot(self, mode: str = ""):
        b = self.bridge
        if b:
            b.reboot(mode)

    def wait_boot(self, timeout_ms: int = 180000) -> bool:
        b = self.bridge
        return b.waitBoot(timeout_ms) if b else False

    def unlock(self, pin: str = "0000") -> bool:
        b = self.bridge
        return b.unlockDevice(pin) if b else False


class _AppHelper:
    """APK installation, uninstallation, and package manager queries."""

    @property
    def bridge(self):
        return get_bridge()

    def install(self, apk_path_or_name: str, extra_args: str = "-r -d") -> str:
        b = self.bridge
        return b.installApk(apk_path_or_name, extra_args) if b else "Error: bridge not found"

    def uninstall(self, package_name: str) -> str:
        b = self.bridge
        return b.uninstallApp(package_name) if b else "Error: bridge not found"

    def is_installed(self, package_name: str) -> bool:
        b = self.bridge
        return b.isAppInstalled(package_name) if b else False

    def launch(self, package_name: str, activity_name: str = "", extras: str = "") -> str:
        b = self.bridge
        if not b:
            return ""
        if activity_name:
            target = f"{package_name}/{activity_name}" if "/" not in activity_name else activity_name
            cmd = f"am start -n {target} {extras}".strip()
        else:
            cmd = f"am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {package_name} {extras}".strip()
        return b.executeShell(cmd)

    def force_stop(self, package_name: str) -> str:
        b = self.bridge
        return b.executeShell(f"am force-stop {package_name}") if b else ""


class _LogcatHelper:
    """Logcat buffer filtering, streaming, and pattern matching."""

    @property
    def bridge(self):
        return get_bridge()

    def clear(self):
        b = self.bridge
        if b:
            b.clearLogcat()

    def get(self, tag: str = "", max_lines: int = 200) -> str:
        b = self.bridge
        return b.getLogcat(tag, max_lines) if b else ""

    def wait_for(self, tag: str, pattern: str, timeout_sec: int = 10) -> str:
        b = self.bridge
        return b.waitForLogcat(tag, pattern, timeout_sec) if b else ""


def log(tag: str, message: str, level: str = "INFO"):
    """Emits a structured log line to the Testbed UI & execution report."""
    b = get_bridge()
    if b:
        b.log(tag, message, level)
    else:
        print(f"[{level}] [{tag}] {message}")


device = _DeviceHelper()
app = _AppHelper()
logcat = _LogcatHelper()
