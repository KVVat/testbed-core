package org.example.project

import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assume
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.example.project.adb.AdbRepository
import org.example.project.junit.JUnitTestExecutor

class PluginImportIntegrationTest {

    @Before
    fun setUp() {
        stopKoin()
        startKoin {
            modules(module {
                single { AdbRepository() }
                single { ToolViewModel() }
                single { JUnitTestExecutor(File(".")) }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun testImportPluginZipAndVerifyCategories() = runBlocking {
        // 1. ZIPファイルの特定
        var rootDir = File(System.getProperty("user.dir"))
        if (rootDir.name == "composeApp") {
            rootDir = rootDir.parentFile
        }
        val zipFile = File(rootDir.parentFile, "testbedui-plugins/build/distributions/plugins-and-resources.zip")
        Assume.assumeTrue("Plugin ZIP file not found. Skipping integration test.", zipFile.exists())

        val pluginsDir = File("plugins")
        
        // バックアップ/クリーンアップ用に事前状態を記録
        val initialPlugins = pluginsDir.listFiles()?.toList() ?: emptyList()

        val viewModel = MainViewModel()
        
        // 3. ZIPインポート実行
        viewModel.importPluginZip(zipFile)
        
        // 非同期のファイルコピーとプラグインロードを待つ
        var retries = 0
        while (viewModel.testPlugins.isEmpty() && retries < 50) {
            delay(100)
            retries++
        }
        
        // プラグインが読み込まれていることを確認
        val plugins = viewModel.testPlugins
        assertTrue(plugins.isNotEmpty(), "Plugins should be loaded after ZIP import")

        // 4. カテゴリの検証
        val expectedCategories = mapOf(
            "FcsCkhExt1HighCredentialsTest" to "crypto",
            "FcsTlscExtTest" to "network",
            "FdpAcc1Test" to "access_control",
            "FdpDarExt1Test" to "data_protection",
            "FdpDarExt2Test" to "data_protection",
            "FdpTudExtTest" to "system",
            "FiaX509TrustStoreTest" to "network",
            "FiaX509ContextUsageTest" to "network",
            "FiaX509ExtensionsTest" to "network",
            "FiaX509ExtTest" to "network",
            "FiaX509RevocationTest" to "network",
            "FiaX509RevocationUnreachableTest" to "network",
            "FprPse1Test" to "data_protection",
            "FptAexExt4Test" to "system",
            "FtpItecExt1" to "network",
            "KernelAcvpTest" to "crypto",
            "LongRunningTest" to "system"
        )

        expectedCategories.forEach { (shortName, expectedCategory) ->
            val plugin = plugins.find { it.shortName == shortName }
            if (plugin != null) {
                assertEquals(expectedCategory, plugin.category, "Category mismatch for $shortName")
            } else {
                println("Skipped verify for ignored/disabled plugin: $shortName")
            }
        }

        // 5. クリーンアップ
        val currentPlugins = pluginsDir.listFiles()?.toList() ?: emptyList()
        currentPlugins.forEach { file ->
            if (!initialPlugins.contains(file)) {
                file.deleteRecursively()
            }
        }
    }
}
