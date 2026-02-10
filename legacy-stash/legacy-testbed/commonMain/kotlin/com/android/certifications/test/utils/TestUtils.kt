package com.android.certifications.test.utils

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences


val settings: Settings= PreferencesSettings(Preferences.userRoot())

fun resource_path():String { return settings.getString("PATH_RESOURCE","") }
fun output_path():String {return settings.getString("PATH_OUTPUT","/tmp/")}

fun isWindows():Boolean {
    return  (System.getProperty("os.name").lowercase().startsWith("windows"))
}