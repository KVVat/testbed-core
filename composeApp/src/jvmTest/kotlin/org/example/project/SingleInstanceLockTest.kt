package org.example.project

import org.example.project.tools.SingleInstanceLock
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceLockTest {

    private val testLockName = "testbed-core-unit-test.lock"

    @BeforeTest
    @AfterTest
    fun cleanup() {
        SingleInstanceLock.release()
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val file = File(tmpDir, testLockName)
        if (file.exists()) {
            file.delete()
        }
    }

    @Test
    fun testAcquireAndRelease() {
        // First acquisition should succeed
        val acquired1 = SingleInstanceLock.acquire(testLockName)
        assertTrue(acquired1, "First acquire should succeed")

        // Second acquisition attempt should fail
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val file = File(tmpDir, testLockName)
        val raf = RandomAccessFile(file, "rw")
        val secondLockAcquired = try {
            val secondLock = raf.channel.tryLock()
            secondLock != null && secondLock.isValid
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            false
        } catch (_: Exception) {
            false
        }
        assertFalse(secondLockAcquired, "Second lock on the same file must fail")
        raf.close()

        // Release lock
        SingleInstanceLock.release()

        // After release, should be able to acquire again
        val acquired2 = SingleInstanceLock.acquire(testLockName)
        assertTrue(acquired2, "Acquire after release should succeed")
        SingleInstanceLock.release()
    }
}
