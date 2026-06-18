plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

tasks.jar {
    archiveFileName.set("mcp-bridge.jar")
    manifest {
        attributes["Main-Class"] = "org.example.project.mcp.StdioBridgeKt"
    }
    // Pack Kotlin stdlib classes into the JAR for standalone execution
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Generate native-like transparent script wrappers on build completion
val createCliWrappers by tasks.registering {
    dependsOn(tasks.jar)
    
    val outputDir = layout.buildDirectory.dir("libs")
    
    doLast {
        val macLinuxScript = outputDir.get().file("testbed-cli").asFile
        val windowsScript = outputDir.get().file("testbed-cli.bat").asFile
        
        // macOS / Linux wrapper script
        macLinuxScript.writeText("""
            #!/bin/bash
            # Automatically resolve path relative to this script directory
            SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            
            # 1. Try to find the bundled runtime in the app package structure
            JAVA_EXEC=""
            if [ -x "${'$'}SCRIPT_DIR/../runtime/Contents/Home/bin/java" ]; then
                JAVA_EXEC="${'$'}SCRIPT_DIR/../runtime/Contents/Home/bin/java"
            elif [ -x "${'$'}SCRIPT_DIR/../../runtime/Contents/Home/bin/java" ]; then
                JAVA_EXEC="${'$'}SCRIPT_DIR/../../runtime/Contents/Home/bin/java"
            elif [ -x "${'$'}SCRIPT_DIR/../runtime/bin/java" ]; then
                JAVA_EXEC="${'$'}SCRIPT_DIR/../runtime/bin/java"
            elif [ -x "${'$'}SCRIPT_DIR/../../runtime/bin/java" ]; then
                JAVA_EXEC="${'$'}SCRIPT_DIR/../../runtime/bin/java"
            fi
            
            # 2. Fallback to JAVA_HOME
            if [ -z "${'$'}JAVA_EXEC" ] && [ -n "${'$'}JAVA_HOME" ] && [ -x "${'$'}JAVA_HOME/bin/java" ]; then
                JAVA_EXEC="${'$'}JAVA_HOME/bin/java"
            fi
            
            # 3. Fallback to system java
            if [ -z "${'$'}JAVA_EXEC" ]; then
                JAVA_EXEC="java"
            fi
            
            exec "${'$'}JAVA_EXEC" -jar "${'$'}SCRIPT_DIR/mcp-bridge.jar" "${'$'}@"
        """.trimIndent().replace("\r\n", "\n"))
        macLinuxScript.setExecutable(true, false)
        
        // Windows wrapper batch file
        windowsScript.writeText("""
            @echo off
            set SCRIPT_DIR=%~dp0
            set JAVA_EXEC=
            
            :: 1. Try to find bundled runtime in app package
            if exist "%SCRIPT_DIR%..\runtime\bin\java.exe" (
                set JAVA_EXEC="%SCRIPT_DIR%..\runtime\bin\java.exe"
            ) else if exist "%SCRIPT_DIR%..\..\runtime\bin\java.exe" (
                set JAVA_EXEC="%SCRIPT_DIR%..\..\runtime\bin\java.exe"
            )
            
            :: 2. Fallback to JAVA_HOME
            if not defined JAVA_EXEC (
                if defined JAVA_HOME (
                    if exist "%JAVA_HOME%\bin\java.exe" (
                        set JAVA_EXEC="%JAVA_HOME%\bin\java.exe"
                    )
                )
            )
            
            :: 3. Fallback to system path java
            if not defined JAVA_EXEC (
                set JAVA_EXEC=java
            )
            
            %JAVA_EXEC% -jar "%SCRIPT_DIR%mcp-bridge.jar" %*
        """.trimIndent().replace("\n", "\r\n"))
        
        println("✅ Stdio Bridge CLI wrappers generated at: ${outputDir.get().asFile.absolutePath}")
    }
}

tasks.assemble {
    dependsOn(createCliWrappers)
}
