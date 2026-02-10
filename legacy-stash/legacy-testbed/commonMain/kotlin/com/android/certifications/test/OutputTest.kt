package com.android.certifications.test

import Platform
import com.android.certifications.test.utils.HostShellHelper
import com.android.certifications.test.utils.SFR
import com.android.certifications.test.utils.output_path
import com.android.certifications.test.utils.resource_path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import logging
import java.io.File
import java.nio.file.Paths


@SFR("System Test","The test for debug and development purpose","output")
class OutputTest {


    @Before
    fun setUp()
    {

    }
    @After
    fun teardown() {
        runBlocking {

        }
    }

    @Test
    fun testOutput() {
        runBlocking{

            logging(resource_path())
            logging(output_path())

            var ret = HostShellHelper.executeCommand("echo hello")
            logging(">"+ret.toString())
            //windows where.exe
            ret = HostShellHelper.executeCommand("which adb")
            logging(">"+ret.toString())
            ret = HostShellHelper.executeCommand("which aaa")
            logging(">"+ret.toString())

            //Thread.sleep(1000)

            //val databasePath =
            val resourcesDir = File(System.getProperty("compose.application.resources.dir"))
            //working directory
            val workingpath = Paths.get("").toAbsolutePath().toString()
            logging("Working dir = $workingpath");
            logging(resourcesDir.resolve("common/file_common.txt").readText())
            logging(System.getProperty("compose.application.resources.dir"));
            logging(Platform().platform+"....")

        }
        logging("ClassName"+this.javaClass.canonicalName);
    }
}