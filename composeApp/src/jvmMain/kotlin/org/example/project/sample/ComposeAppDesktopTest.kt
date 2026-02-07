package org.example.project.sample

import org.example.project.JUnitBridge
import org.example.project.TestLogLevel
import org.example.project.adb.rules.AdbDeviceRule
import org.junit.Rule
import org.junit.Test

class ComposeAppDesktopTest {

    @get:Rule
    val adbDeviceRule = AdbDeviceRule()

    // JUnitBridge を通じて本体のログ機能を利用
    private val logger = JUnitBridge.logging

    @Test
    fun outputTest() {
        logger?.invoke("--- TEST START: outputTest ---", TestLogLevel.INFO)
    }
}
