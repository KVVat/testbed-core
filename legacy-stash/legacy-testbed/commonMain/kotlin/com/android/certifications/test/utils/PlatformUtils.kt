package com.android.certifications.test.utils

import java.util.Locale

class PlatformUtils {
    companion object {
        val IS_WINDOWS =
            System.getProperty("os.name").lowercase(Locale.getDefault()).startsWith("win")
        val IS_MAC = System.getProperty("os.name").lowercase(Locale.getDefault()).startsWith("mac")
        val IS_LINUX =
            System.getProperty("os.name").lowercase(Locale.getDefault()).startsWith("linux")

        val SHELLCMD = if(IS_WINDOWS) "cmd" else if(IS_MAC|| IS_LINUX) "bash" else "bash"
        val SHELLCMDPREFIX =if(IS_WINDOWS) "" else if(IS_MAC|| IS_LINUX) "#!/bin/bash" else "#!/bin/bash"

        val LINE_SEPARATOR = System.getProperty("line.separator")
    }
}