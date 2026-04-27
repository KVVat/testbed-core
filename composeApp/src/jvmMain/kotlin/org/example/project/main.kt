package org.example.project

import org.example.project.App
import androidx.compose.ui.window.Window
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.example.project.adb.AdbRepository
import org.example.project.ToolViewModel
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import org.jetbrains.compose.resources.painterResource
import testbed_core.composeapp.generated.resources.Res
import testbed_core.composeapp.generated.resources.icon
import java.awt.Taskbar
import javax.imageio.ImageIO
import java.io.File

import kotlin.system.exitProcess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun main() {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    
    println("[BOOT] Application starting...")
    
    startKoin {
        modules(module {
            single { AdbRepository() }
            single { ToolViewModel() }
        })
    }
    println("[BOOT] os.name=${System.getProperty("os.name")}")
    println("[BOOT] java.home=${System.getProperty("java.home")}")
    println("[BOOT] isWindows=$isWindows, transparent=${!isWindows}")
    
    try {
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                val iconFile = File("icon1024x1024.png")
                if (iconFile.exists()) {
                    taskbar.iconImage = ImageIO.read(iconFile)
                }
            }
        }
    } catch (e: Exception) {
        // Ignore
    }

    application {
        val windowState = rememberWindowState()

    Window(
        onCloseRequest = {
            exitApplication()
            exitProcess(0)
        },
        state = windowState,
        title = "Testbed Core",
        icon = painterResource(Res.drawable.icon),
        undecorated = true,
        transparent = !isWindows  // Windowsではtransparent非対応(VM環境でウィンドウが見えなくなる)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF3C3F41), RoundedCornerShape(10.dp)),
            color = Color(0xFF2B2D30)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                WindowDraggableArea {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(Color(0xFF2B2D30)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Testbed Core",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // macOS style traffic lights
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Close
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5F56))
                                    .clickable {
                                        exitApplication()
                                        exitProcess(0)
                                    }
                            )
                            // Minimize
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFBD2E))
                                    .clickable {
                                        windowState.isMinimized = true
                                    }
                            )
                            // Maximize/Restore
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF27C93F))
                                    .clickable {
                                        if (windowState.placement == WindowPlacement.Maximized) {
                                            windowState.placement = WindowPlacement.Floating
                                        } else {
                                            windowState.placement = WindowPlacement.Maximized
                                        }
                                    }
                            )
                        }
                    }
                }
                App()
            }
        }
    }
}
}