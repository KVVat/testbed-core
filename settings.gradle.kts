pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "testbed-core"
include(":composeApp")
include(":tools:mutton-agent")
include(":tools:mcp-bridge")