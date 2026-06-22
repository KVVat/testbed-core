plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    // 1. JVM Target
    jvm {
        withJava()
    }

    // 2. Native Target based on Host OS to avoid cross-compilation errors
    val hostOs = System.getProperty("os.name").lowercase()
    when {
        hostOs.contains("mac") -> {
            macosX64 {
                binaries { executable { entryPoint = "org.example.project.mcp.main" } }
            }
            macosArm64 {
                binaries { executable { entryPoint = "org.example.project.mcp.main" } }
            }
        }
        hostOs.contains("win") -> {
            mingwX64 {
                binaries { executable { entryPoint = "org.example.project.mcp.main" } }
            }
        }
        hostOs.contains("nux") || hostOs.contains("nand") -> {
            linuxX64 {
                binaries { executable { entryPoint = "org.example.project.mcp.main" } }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.2.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-cio:3.2.3")
            }
        }
        
        val nativeMain by creating {
            dependsOn(commonMain)
        }

        if (hostOs.contains("mac")) {
            val macosX64Main by getting { dependsOn(nativeMain) }
            val macosArm64Main by getting { dependsOn(nativeMain) }
            macosX64Main.dependencies { implementation("io.ktor:ktor-client-darwin:3.2.3") }
            macosArm64Main.dependencies { implementation("io.ktor:ktor-client-darwin:3.2.3") }
        }
        if (hostOs.contains("win")) {
            val mingwX64Main by getting { dependsOn(nativeMain) }
            mingwX64Main.dependencies { implementation("io.ktor:ktor-client-winhttp:3.2.3") }
        }
        if (hostOs.contains("nux") || hostOs.contains("nand")) {
            val linuxX64Main by getting { dependsOn(nativeMain) }
            linuxX64Main.dependencies { implementation("io.ktor:ktor-client-curl:3.2.3") }
        }
    }
}

tasks.named<Jar>("jvmJar") {
    manifest {
        attributes["Main-Class"] = "org.example.project.mcp.StdioBridgeKt"
    }
    val runtimeClasspath = configurations.named("jvmRuntimeClasspath").get()
    from(runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Copy compiled native executable to tools/ root directory (macOS only)
val copyNativeBridgeToTools by tasks.registering {
    val hostOs = System.getProperty("os.name").lowercase()
    if (hostOs.contains("mac")) {
        dependsOn("linkReleaseExecutableMacosArm64")
        
        val pDir = projectDir
        val toolsDir = pDir.parentFile // rootProject/tools/
        
        inputs.file(File(pDir, "build/bin/macosArm64/releaseExecutable/mcp-bridge.kexe"))
        outputs.file(File(toolsDir, "mcp-bridge.kexe"))
        
        doLast {
            val srcFile = File(pDir, "build/bin/macosArm64/releaseExecutable/mcp-bridge.kexe")
            val destFile = File(toolsDir, "mcp-bridge.kexe")
            
            toolsDir.mkdirs()
            
            if (srcFile.exists()) {
                srcFile.copyTo(destFile, overwrite = true)
                destFile.setExecutable(true, false)
                println("✅ Copied Native Bridge binary to: ${destFile.absolutePath}")
            } else {
                val x64File = File(pDir, "build/bin/macosX64/releaseExecutable/mcp-bridge.kexe")
                if (x64File.exists()) {
                    x64File.copyTo(destFile, overwrite = true)
                    destFile.setExecutable(true, false)
                    println("✅ Copied Native Bridge (x64) binary to: ${destFile.absolutePath}")
                }
            }
        }
    }
}

tasks.assemble {
    dependsOn(copyNativeBridgeToTools)
}
