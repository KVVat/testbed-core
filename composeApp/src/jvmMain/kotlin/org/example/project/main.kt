package org.example.project

import org.example.project.App
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

import kotlin.system.exitProcess

fun main() = application {
    Window(
        onCloseRequest = {
            exitApplication()
            exitProcess(0)
        },
        title = "KotlinProject",
    ) {
        App()
    }
}