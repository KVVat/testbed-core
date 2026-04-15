import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
repositories {
    google()
    mavenCentral()
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/developer") }
}

java {
    // Javaコンパイルのターゲットを固定
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")

            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.adam)
            implementation(libs.junit) // commonMainでJUnitを利用可能にする
            implementation(libs.gson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.cors)
            implementation(libs.mcp.kotlin.sdk)
        }
    }
}

//Build Command ./gradlew :composeApp:createReleaseDistributable
compose.desktop {
    application {
        mainClass = "org.example.project.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe, // Windows用に追加
                TargetFormat.Deb, // Linux用
                TargetFormat.AppImage // Linuxでポータブルに動かすならこれも便利
            )
            packageName = "TestbedCore" // アプリの実行ファイル名になります
            packageVersion = "1.0.0"
            modules("java.management", "java.naming", "jdk.unsupported", "java.sql")
            // OSごとの設定 (アイコンなどがあればここで指定可能)
            macOS {
                iconFile.set(project.file("src/jvmMain/composeResources/files/icon.icns"))
            }
            windows {
                menuGroup = "Testbed Tools"
            }
            linux {
                // shortcut = true
            }
        }
//        nativeDistributions {
//            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
//            packageName = "org.example.project"
//            packageVersion = "1.0.0"
//        }
    }
}

afterEvaluate {
    tasks.findByName("run")?.dependsOn(":tools:mutton-agent:copyTestApk")
}
